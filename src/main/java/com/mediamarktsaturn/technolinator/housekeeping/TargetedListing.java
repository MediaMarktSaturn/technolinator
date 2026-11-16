package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import com.mediamarktsaturn.technolinator.sbom.RepositoryProjectMatcher;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Collects what the {@link UnitMatcher} needs to know for a {@link HousekeepingSelection}, without listing everything of GitHub and
 * Dependency-Track like a complete run does:
 * <ol>
 *     <li>the selected projects are fetched, by UUID or by name, including their hierarchy (ancestors and descendants)</li>
 *     <li>the selected repositories, and the ones the selected projects reference, are fetched on their own, see
 *     {@link GitHubRepositoryIndexer#build(HousekeepingRun, Collection)}</li>
 *     <li>the projects of these repositories are searched by the repository name and matched the same way as the event handling does,
 *     see {@link RepositoryProjectMatcher}, again including their hierarchy</li>
 * </ol>
 * The result is shaped like the complete listings, so it can be matched and processed the very same way, but it only knows about what was asked for.
 * That has some consequences compared to a complete run:
 * <ul>
 *     <li>a project is only found by the name of its repository, or if it's selected explicitly. Projects of a repository that are named
 *     differently and don't reference the repository are not found</li>
 *     <li>whether a project is claimed by several repositories can only be told for the repositories looked at</li>
 *     <li>repositories can only be looked at with their owner, as that's needed to find the installation of the GitHub app</li>
 * </ul>
 */
final class TargetedListing {

    /**
     * What's known about the selection
     *
     * @param index    The GitHub repositories asked for, or referenced by projects asked for
     * @param projects The Dependency-Track projects found, including their hierarchies
     */
    record Listing(GitHubRepositoryIndex index, List<DependencyTrackProject> projects) {
    }

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /**
     * Guards against cyclic hierarchies, which Dependency-Track shouldn't allow, though
     */
    private static final int MAX_DEPTH = 32;
    private static final int CONCURRENCY = 4;

    private final GitHubRepositoryIndexer indexer;
    private final DependencyTrackClient dtrack;

    TargetedListing(GitHubRepositoryIndexer indexer, DependencyTrackClient dtrack) {
        this.indexer = indexer;
        this.dtrack = dtrack;
    }

    /**
     * @return the repository given as {@code owner/name}, or empty if it's not given including its owner
     */
    static Optional<GitHubRepositoryIndexer.Target> parseRepository(String repository) {
        var parts = repository.trim().split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepositoryIndexer.Target(parts[0].trim(), parts[1].trim()));
    }

    Uni<Listing> load(HousekeepingRun run, HousekeepingSelection selection) {
        return findSelectedProjects(selection.projects())
            .chain(this::withHierarchy)
            .chain(selected -> {
                var targets = new LinkedHashSet<GitHubRepositoryIndexer.Target>();
                selection.repositories().stream().map(TargetedListing::parseRepository).forEach(target -> target.ifPresent(targets::add));
                selected.stream()
                    .flatMap(project -> UnitMatcher.referencesOf(project).stream())
                    .forEach(reference -> targets.add(new GitHubRepositoryIndexer.Target(reference.owner(), reference.name())));
                run.writeToMDC();
                Log.infof("Looking at %d repositories and %d projects of the selection", targets.size(), selected.size());

                return indexer.build(run, targets)
                    .chain(index -> projectsOfRepositories(index, targets)
                        .chain(found -> {
                            var all = new ArrayList<>(selected);
                            all.addAll(found);
                            return withHierarchy(all);
                        })
                        .map(projects -> new Listing(index, projects)));
            });
    }

    private Uni<List<DependencyTrackProject>> findSelectedProjects(Collection<String> selectors) {
        return collect(selectors, selector -> {
            if (UUID.matcher(selector).matches()) {
                return dtrack.getProject(selector).map(project -> project.stream().toList());
            }
            return dtrack.searchProjects(selector)
                .map(candidates -> candidates.stream().filter(project -> selector.equalsIgnoreCase(project.name())).toList());
        });
    }

    /**
     * @return the projects belonging to the repositories (in any version), or referencing them if they're gone
     */
    private Uni<List<DependencyTrackProject>> projectsOfRepositories(GitHubRepositoryIndex index, Collection<GitHubRepositoryIndexer.Target> targets) {
        return collect(targets, target -> dtrack.searchProjects(target.name()).map(candidates -> {
            var found = new ArrayList<DependencyTrackProject>();
            index.repositories().stream()
                .filter(repository -> repository.reference().owner().equals(target.owner()) && repository.reference().name().equals(target.name()))
                .findFirst()
                .ifPresent(repository -> found.addAll(RepositoryProjectMatcher.select(candidates, repository.name(), repository.gitUrl(), repository.htmlUrl())));
            candidates.stream()
                .filter(project -> UnitMatcher.referencesOf(project).stream()
                    .anyMatch(reference -> reference.owner().equals(target.owner()) && reference.name().equals(target.name())))
                .forEach(found::add);
            return found;
        }));
    }

    /**
     * Adds the ancestors and descendants of the projects. Ancestors tell which repository a project follows,
     * and Dependency-Track only archives parents without active children, so hierarchies must not be split.
     */
    private Uni<List<DependencyTrackProject>> withHierarchy(Collection<DependencyTrackProject> seeds) {
        var known = new LinkedHashMap<String, DependencyTrackProject>();
        seeds.forEach(project -> known.putIfAbsent(project.uuid(), project));
        var seedList = List.copyOf(known.values());
        return addAncestors(known, seedList, 0)
            .chain(() -> addDescendants(known, seedList, 0))
            .map(ignored -> List.copyOf(known.values()));
    }

    private Uni<Void> addAncestors(Map<String, DependencyTrackProject> known, List<DependencyTrackProject> frontier, int depth) {
        var missing = frontier.stream().flatMap(project -> project.parentUuid().stream()).filter(uuid -> !known.containsKey(uuid)).distinct().toList();
        if (missing.isEmpty() || depth >= MAX_DEPTH) {
            return Uni.createFrom().voidItem();
        }
        return collect(missing, uuid -> dtrack.getProject(uuid).map(project -> project.stream().toList()))
            .chain(parents -> addAncestors(known, addNew(known, parents), depth + 1));
    }

    private Uni<Void> addDescendants(Map<String, DependencyTrackProject> known, List<DependencyTrackProject> frontier, int depth) {
        if (frontier.isEmpty() || depth >= MAX_DEPTH) {
            return Uni.createFrom().voidItem();
        }
        return collect(frontier, project -> dtrack.listChildren(project.uuid()))
            .chain(children -> addDescendants(known, addNew(known, children), depth + 1));
    }

    private static List<DependencyTrackProject> addNew(Map<String, DependencyTrackProject> known, List<DependencyTrackProject> projects) {
        var added = new ArrayList<DependencyTrackProject>();
        projects.forEach(project -> {
            if (known.putIfAbsent(project.uuid(), project) == null) {
                added.add(project);
            }
        });
        return added;
    }

    private static <T> Uni<List<DependencyTrackProject>> collect(Collection<T> items, Function<T, Uni<List<DependencyTrackProject>>> lookup) {
        return Multi.createFrom().iterable(items)
            .onItem().<List<DependencyTrackProject>>transformToUni(lookup::apply)
            .merge(CONCURRENCY)
            .collect().asList()
            .map(results -> results.stream().flatMap(List::stream).toList());
    }
}
