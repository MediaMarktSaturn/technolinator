package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import org.jboss.logging.MDC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The one piece of work the {@link HousekeepingWorker} is handed: a GitHub repository together with the Dependency-Track projects belonging
 * to it, matched the same way as Technolinator names and references them when uploading. Every repository and every project is part of
 * exactly one unit, so every one of them is looked at once per run.
 * <p>
 * Either side might be missing:
 * <ul>
 *     <li><b>repository without projects</b>: the repository wasn't analyzed (yet), or the projects couldn't be matched</li>
 *     <li><b>projects without repository</b>: no repository could be matched. The [repositoryState] tells, whether it's certain that
 *     the repository doesn't exist ({@link GitHubRepositoryIndex.State#GONE}), or whether nothing can be said
 *     ({@link GitHubRepositoryIndex.State#UNKNOWN}), e.g. for projects not referencing a repository at all, or repositories
 *     of accounts the GitHub app isn't installed for.</li>
 * </ul>
 *
 * @param run             The housekeeping run this unit belongs to
 * @param repository      The GitHub repository, if one was matched
 * @param projects        The Dependency-Track projects in any version and state (active or archived), including parents and children, if any were matched
 * @param repositoryState {@code ACTIVE} or {@code ARCHIVED} if there is a [repository], {@code GONE}, {@code MOVED} or {@code UNKNOWN} otherwise
 * @param moved           The rename of the repository the [projects] reference, if the [repositoryState] is {@code MOVED}
 */
public record HousekeepingUnit(
    HousekeepingRun run,
    Optional<GitHubRepo> repository,
    List<DependencyTrackProject> projects,
    GitHubRepositoryIndex.State repositoryState,
    Optional<Move> moved
) {

    /**
     * A repository that doesn't exist under its old name anymore, as it was renamed
     *
     * @param from The old name of the repository, as the projects reference it
     * @param to   The repository, which is named differently now
     */
    public record Move(RepositoryReference from, GitHubRepo to) {
    }

    /**
     * Guards against cyclic hierarchies, which Dependency-Track shouldn't allow, though
     */
    private static final int MAX_DEPTH = 32;

    public HousekeepingUnit {
        projects = List.copyOf(projects);
        var repositoryStateWithRepository = repositoryState == GitHubRepositoryIndex.State.ACTIVE || repositoryState == GitHubRepositoryIndex.State.ARCHIVED;
        if (repository.isPresent() != repositoryStateWithRepository) {
            throw new IllegalArgumentException("State %s doesn't fit to a unit %s a repository".formatted(repositoryState, repository.isPresent() ? "with" : "without"));
        }
        if (moved.isPresent() != (repositoryState == GitHubRepositoryIndex.State.MOVED)) {
            throw new IllegalArgumentException("State %s doesn't fit to a unit %s a renamed repository".formatted(repositoryState, moved.isPresent() ? "with" : "without"));
        }
        if (repository.isEmpty() && projects.isEmpty()) {
            throw new IllegalArgumentException("A unit needs a repository or projects");
        }
    }

    public HousekeepingUnit(HousekeepingRun run, Optional<GitHubRepo> repository, List<DependencyTrackProject> projects, GitHubRepositoryIndex.State repositoryState) {
        this(run, repository, projects, repositoryState, Optional.empty());
    }

    public static HousekeepingUnit of(HousekeepingRun run, GitHubRepo repository, List<DependencyTrackProject> projects) {
        return new HousekeepingUnit(run, Optional.of(repository), projects,
            repository.archived() ? GitHubRepositoryIndex.State.ARCHIVED : GitHubRepositoryIndex.State.ACTIVE);
    }

    public static HousekeepingUnit withoutRepository(HousekeepingRun run, List<DependencyTrackProject> projects, GitHubRepositoryIndex.State state) {
        return new HousekeepingUnit(run, Optional.empty(), projects, state);
    }

    /**
     * A unit of projects referencing the repository [from], which was renamed to [to]
     */
    public static HousekeepingUnit ofRenamedRepository(HousekeepingRun run, List<DependencyTrackProject> projects, RepositoryReference from, GitHubRepo to) {
        return new HousekeepingUnit(run, Optional.empty(), projects, GitHubRepositoryIndex.State.MOVED, Optional.of(new Move(from, to)));
    }

    /**
     * @return the repository of the unit, or the one it was renamed to
     */
    public Optional<GitHubRepo> repositoryOrRenamed() {
        return repository.or(() -> moved.map(Move::to));
    }

    public boolean dryRun() {
        return run.dryRun();
    }

    /**
     * @return true if the repository is known to be gone or archived, which is the case for projects not being useful any longer
     */
    public boolean repositoryGoneOrArchived() {
        return repositoryState == GitHubRepositoryIndex.State.GONE || repositoryState == GitHubRepositoryIndex.State.ARCHIVED;
    }

    public List<DependencyTrackProject> activeProjects() {
        return projects.stream().filter(DependencyTrackProject::active).toList();
    }

    /**
     * Groups the active projects by their depth in the hierarchy, deepest first. So the projects of a group never are parents of each other,
     * and all children are contained in a group preceding the one of their parent.
     * Dependency-Track rejects archiving a parent having active children, so this is the order to work on projects in.
     */
    public List<List<DependencyTrackProject>> activeProjectsChildrenFirst() {
        return groupByDepth(activeProjects(), true);
    }

    /**
     * Groups the given projects, which are expected to be part of this unit, by their depth in the hierarchy, parents first.
     * So the projects of a group never are parents of each other, and all parents are contained in a group preceding the ones of their children.
     * This is the order to work on projects in, whose children shouldn't be active without their parent.
     */
    public List<List<DependencyTrackProject>> groupParentsFirst(List<DependencyTrackProject> subset) {
        return groupByDepth(subset, false);
    }

    /**
     * Groups the given projects, which are expected to be part of this unit, by their depth in the hierarchy, children first.
     * So the projects of a group never are parents of each other, and all children are contained in a group preceding the one of their parent.
     * This is the order to work on projects in, whose parents can't be archived with active children.
     */
    public List<List<DependencyTrackProject>> groupChildrenFirst(List<DependencyTrackProject> subset) {
        return groupByDepth(subset, true);
    }

    private List<List<DependencyTrackProject>> groupByDepth(List<DependencyTrackProject> subset, boolean deepestFirst) {
        Map<String, String> parents = new HashMap<>();
        projects.forEach(project -> project.parentUuid().ifPresent(parent -> parents.put(project.uuid(), parent)));

        var byDepth = new TreeMap<Integer, List<DependencyTrackProject>>(deepestFirst ? Comparator.reverseOrder() : Comparator.naturalOrder());
        for (var project : subset) {
            byDepth.computeIfAbsent(depth(project.uuid(), parents), depth -> new ArrayList<>()).add(project);
        }
        return List.copyOf(byDepth.values());
    }

    private static int depth(String uuid, Map<String, String> parents) {
        var depth = 0;
        var current = parents.get(uuid);
        while (current != null && depth < MAX_DEPTH) {
            depth++;
            current = parents.get(current);
        }
        return depth;
    }

    /**
     * Human readable identification for log messages
     */
    public String subject() {
        return repository
            .map(repo -> "repository " + repo.fullName())
            .orElseGet(() -> "project " + projects.getFirst().displayName() + (projects.size() > 1 ? " and %d more".formatted(projects.size() - 1) : "")
                + moved.map(move -> " of a repository renamed to " + move.to().fullName()).orElse(" without repository"));
    }

    /**
     * Replaces the MDC of the current thread with the trace id of the run and the repository of this unit.
     * Worker threads are shared with the event handling, so remainders of other processes need to be removed.
     */
    public void writeToMDC() {
        run.writeToMDC();
        repositoryOrRenamed().ifPresent(repo -> MDC.put("repository", repo.fullName()));
    }
}
