package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import com.mediamarktsaturn.technolinator.sbom.RepositoryProjectMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Pairs the repositories of GitHub with the projects of Dependency-Track and creates the {@link HousekeepingUnit}s for the worker.
 * <p>
 * The projects of a repository are determined by the {@link RepositoryProjectMatcher}, the very same way as the event handling does.
 * Projects that are claimed by more than one repository (e.g. same name in different accounts, without references to tell them apart)
 * are not given to any of them, as it's unclear which one is right.
 * <p>
 * Additionally, projects not claimed by any repository follow their nearest claimed ancestor in the project hierarchy, regardless of their name
 * or references. Dependency-Track only archives parents without active children, so a hierarchy must not be split across units.
 * A descendant claimed by another repository on its own stays with that repository, though, and descendants of contested projects are contested as well.
 * <p>
 * Projects not belonging to any repository are given to the worker anyway, grouped by the repository they reference, or else by the one
 * their nearest ancestor referencing exactly one repository does.
 * The state of the unit tells whether that repository is certainly gone, was renamed (the unit knows the new repository then), or whether
 * nothing can be said. A project referencing a repository, that exists but wasn't matched (e.g. because it's named differently), is not considered gone.
 * <p>
 * Repositories are filtered like the event handling does, by the names in {@code app.enabled_repos}. This includes the projects
 * related to the filtered repositories, which are left alone. Projects that can't be related to a repository, are left alone if a filter is set.
 */
final class UnitMatcher {

    /**
     * Guards against cyclic hierarchies, which Dependency-Track shouldn't allow, though
     */
    private static final int MAX_DEPTH = 32;

    private UnitMatcher() {
    }

    /**
     * @param enabledRepos Lower case names of the repositories to take care of, empty for all
     * @return units for repositories first, ordered by name, followed by the ones for projects without a repository
     */
    static List<HousekeepingUnit> match(
        HousekeepingRun run,
        GitHubRepositoryIndex index,
        List<DependencyTrackProject> projects,
        Set<String> enabledRepos
    ) {
        var byUuid = new LinkedHashMap<String, DependencyTrackProject>();
        projects.forEach(project -> byUuid.putIfAbsent(project.uuid(), project));

        var byPossibleRepoName = new HashMap<String, List<DependencyTrackProject>>();
        for (var project : byUuid.values()) {
            for (var repoName : RepositoryProjectMatcher.possibleRepoNames(project.name())) {
                byPossibleRepoName.computeIfAbsent(repoName, name -> new ArrayList<>()).add(project);
            }
        }

        var repositories = index.repositories();
        // uuid of a project -> indexes of the repositories claiming it
        var claims = new HashMap<String, List<Integer>>();
        for (int i = 0; i < repositories.size(); i++) {
            var repository = repositories.get(i);
            var candidates = byPossibleRepoName.getOrDefault(repository.name(), List.of());
            for (var project : RepositoryProjectMatcher.select(candidates, repository.name(), repository.gitUrl(), repository.htmlUrl())) {
                claims.computeIfAbsent(project.uuid(), uuid -> new ArrayList<>()).add(i);
            }
        }

        var projectsOfRepository = new ArrayList<List<DependencyTrackProject>>();
        repositories.forEach(repository -> projectsOfRepository.add(new ArrayList<>()));
        var unmatched = new ArrayList<DependencyTrackProject>();
        for (var project : byUuid.values()) {
            var claim = nearest(project, byUuid, candidate -> Optional.ofNullable(claims.get(candidate.uuid())));
            if (claim.isPresent() && claim.get().size() == 1) {
                projectsOfRepository.get(claim.get().getFirst()).add(project);
            } else {
                // not claimed, or contested
                unmatched.add(project);
            }
        }

        var units = new ArrayList<HousekeepingUnit>();
        for (int i = 0; i < repositories.size(); i++) {
            var repository = repositories.get(i);
            if (isEnabled(enabledRepos, repository.name())) {
                units.add(HousekeepingUnit.of(run, repository, projectsOfRepository.get(i)));
            }
        }

        units.addAll(unitsWithoutRepository(run, index, unmatched, byUuid, enabledRepos));
        return units;
    }

    /**
     * @return the first result of [lookup] for the project itself or its ancestors, nearest first
     */
    private static <T> Optional<T> nearest(
        DependencyTrackProject project,
        Map<String, DependencyTrackProject> byUuid,
        Function<DependencyTrackProject, Optional<T>> lookup
    ) {
        var current = project;
        for (int depth = 0; current != null && depth <= MAX_DEPTH; depth++) {
            var result = lookup.apply(current);
            if (result.isPresent()) {
                return result;
            }
            current = current.parentUuid().map(byUuid::get).orElse(null);
        }
        return Optional.empty();
    }

    private static List<HousekeepingUnit> unitsWithoutRepository(
        HousekeepingRun run,
        GitHubRepositoryIndex index,
        List<DependencyTrackProject> unmatched,
        Map<String, DependencyTrackProject> byUuid,
        Set<String> enabledRepos
    ) {
        var singleReferences = new HashMap<String, RepositoryReference>();
        for (var project : unmatched) {
            var references = referencesOf(project);
            if (references.size() == 1) {
                singleReferences.put(project.uuid(), references.getFirst());
            }
        }

        var byReference = new LinkedHashMap<RepositoryReference, List<DependencyTrackProject>>();
        var withoutSingleReference = new ArrayList<DependencyTrackProject>();
        for (var project : unmatched) {
            nearest(project, byUuid, candidate -> Optional.ofNullable(singleReferences.get(candidate.uuid())))
                .ifPresentOrElse(
                    reference -> byReference.computeIfAbsent(reference, ref -> new ArrayList<>()).add(project),
                    () -> withoutSingleReference.add(project));
        }

        var units = new ArrayList<HousekeepingUnit>();
        byReference.forEach((reference, projects) -> {
            if (isEnabled(enabledRepos, reference.name())) {
                // if the repository exists although the projects weren't matched to it, it's not for us to judge
                var state = index.lookup(reference);
                var movedTo = state == GitHubRepositoryIndex.State.MOVED ? index.movedTo(reference) : Optional.<GitHubRepo>empty();
                if (movedTo.isPresent()) {
                    units.add(HousekeepingUnit.ofRenamedRepository(run, projects, reference, movedTo.get()));
                } else {
                    units.add(HousekeepingUnit.withoutRepository(run, projects,
                        state == GitHubRepositoryIndex.State.GONE ? GitHubRepositoryIndex.State.GONE : GitHubRepositoryIndex.State.UNKNOWN));
                }
            }
        });
        if (enabledRepos.isEmpty()) {
            withoutSingleReference.forEach(project ->
                units.add(HousekeepingUnit.withoutRepository(run, List.of(project), GitHubRepositoryIndex.State.UNKNOWN)));
        }
        return units;
    }

    /**
     * @return the distinct repositories a project references, which might be none or several
     */
    static List<RepositoryReference> referencesOf(DependencyTrackProject project) {
        return project.repositoryUrls().stream()
            .map(RepositoryReference::parse)
            .flatMap(Optional::stream)
            .distinct()
            .toList();
    }

    private static boolean isEnabled(Set<String> enabledRepos, String repoName) {
        return enabledRepos.isEmpty() || enabledRepos.contains(repoName.toLowerCase(Locale.ROOT));
    }
}
