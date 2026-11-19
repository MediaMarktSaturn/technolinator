package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.sbom.ConfiguredProjects;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import com.mediamarktsaturn.technolinator.sbom.RepositoryProjectMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Decides which Dependency-Track projects of a {@link HousekeepingUnit} are to be archived or reactivated, which is one decision made from
 * everything known about the unit, instead of several ones made independently: so the rules can't work against each other (e.g. reactivating a
 * child below a parent that's archived at the same time), and the repository's configuration is read once at most.
 * <p>
 * Rules, each switched on separately:
 * <ul>
 *     <li><b>archive orphaned projects</b>: all active projects of a repository that is archived or doesn't exist anymore</li>
 *     <li><b>archive removed modules</b>: the active child projects of an active repository, whose module isn't described by the repository's
 *     configuration anymore, see {@link ConfiguredModules}. A project with active descendants that aren't obsolete themselves is kept,
 *     as Dependency-Track rejects archiving it</li>
 *     <li><b>reactivate projects</b>: archived projects of an active repository, of which no version is active. Of several archived versions,
 *     the one with the latest BOM import, none if that can't be told. Children of removed modules (if that rule is on) and children whose parent
 *     stays archived, or gets archived, stay archived</li>
 * </ul>
 * Only projects definitely belonging to an active repository ({@link RepositoryProjectMatcher#select}) are touched by the latter two.
 * This is pure planning on the snapshot of the run, the {@link ProjectStateChanger} verifies it against the current state before changing anything.
 */
final class ProjectStatePlanner {

    private ProjectStatePlanner() {
    }

    /**
     * Why the state of a project is changed
     */
    enum Reason {
        REPOSITORY_ARCHIVED(true, "the repository is archived"),
        REPOSITORY_GONE(true, "the repository doesn't exist anymore"),
        MODULE_REMOVED(true, "their module isn't part of the repository's configuration anymore"),
        SUPERSEDED(true, "they can't be renamed, as the new name is taken already"),
        REPOSITORY_ACTIVE(false, "they belong to the active repository");

        final boolean archive;
        final String description;

        Reason(boolean archive, String description) {
            this.archive = archive;
            this.description = description;
        }

        /**
         * @return the value for the structured log
         */
        String logValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A project to archive or to reactivate
     */
    record Change(DependencyTrackProject project, Reason reason) {

        boolean archive() {
            return reason.archive;
        }
    }

    /**
     * The switches of the rules
     */
    record Rules(boolean archiveOrphaned, boolean archiveRemovedModules, boolean reactivate) {

        boolean any() {
            return archiveOrphaned || archiveRemovedModules || reactivate;
        }
    }

    /**
     * The names of the projects a repository's configuration describes, as the analysis of the default branch names them
     * (see {@link ConfiguredProjects}), and whether the configuration enables the analysis.
     * Without configuration, the repository itself is the only project.
     */
    record ConfiguredModules(Set<String> names, boolean enabled) {

        static ConfiguredModules of(String repositoryName, Optional<TechnolinatorConfig> config) {
            return new ConfiguredModules(
                ConfiguredProjects.projectNames(repositoryName, config),
                config.map(TechnolinatorConfig::enable).orElse(true)
            );
        }

        boolean describes(DependencyTrackProject project) {
            return names.stream().anyMatch(name -> name.equalsIgnoreCase(project.name()));
        }
    }

    /**
     * Cheap check on the snapshot, whether {@link #plan} might change anything for the unit at all
     */
    static boolean mightApply(HousekeepingUnit unit, Rules rules) {
        return switch (unit.repositoryState()) {
            case ARCHIVED, GONE -> rules.archiveOrphaned() && !unit.activeProjects().isEmpty();
            case ACTIVE -> (rules.archiveRemovedModules() && unit.activeProjects().stream().anyMatch(ProjectStatePlanner::isChild))
                || (rules.reactivate() && unit.projects().stream().anyMatch(project -> !project.active()));
            default -> false;
        };
    }

    /**
     * @return whether {@link #plan} needs to know the configuration of the unit's repository, which costs requests to GitHub
     */
    static boolean needsConfiguration(HousekeepingUnit unit, Rules rules) {
        if (!rules.archiveRemovedModules() || unit.repositoryState() != GitHubRepositoryIndex.State.ACTIVE) {
            return false;
        }
        var belonging = belonging(unit);
        if (belonging.stream().anyMatch(project -> project.active() && isChild(project))) {
            return true;
        }
        // archived children of removed modules must not be reactivated
        return rules.reactivate() && reactivations(unit, belonging, Set.of(), project -> false).stream().anyMatch(ProjectStatePlanner::isChild);
    }

    /**
     * @param modules The configuration of the unit's repository, required if {@link #needsConfiguration} tells so
     * @return the changes to make, archiving ones first
     */
    static List<Change> plan(HousekeepingUnit unit, Rules rules, Optional<ConfiguredModules> modules) {
        return switch (unit.repositoryState()) {
            case ARCHIVED -> rules.archiveOrphaned() ? archiveAll(unit, Reason.REPOSITORY_ARCHIVED) : List.of();
            case GONE -> rules.archiveOrphaned() ? archiveAll(unit, Reason.REPOSITORY_GONE) : List.of();
            case ACTIVE -> planForActiveRepository(unit, rules, modules);
            default -> List.of();
        };
    }

    private static List<Change> archiveAll(HousekeepingUnit unit, Reason reason) {
        return unit.activeProjects().stream().map(project -> new Change(project, reason)).toList();
    }

    private static List<Change> planForActiveRepository(HousekeepingUnit unit, Rules rules, Optional<ConfiguredModules> modules) {
        if (modules.isEmpty() && needsConfiguration(unit, rules)) {
            throw new IllegalArgumentException("The configuration of the repository is required to plan " + unit.subject());
        }
        var belonging = belonging(unit);
        var changes = new ArrayList<Change>();

        if (rules.archiveRemovedModules() && modules.filter(ConfiguredModules::enabled).isPresent()) {
            obsoleteModules(unit, belonging, modules.get()).forEach(project -> changes.add(new Change(project, Reason.MODULE_REMOVED)));
        }

        if (rules.reactivate()) {
            var archiving = changes.stream().map(change -> change.project().uuid()).collect(Collectors.toSet());
            // modules removed from git stay archived, regardless of whether the configuration enables the analysis
            Predicate<DependencyTrackProject> excluded = rules.archiveRemovedModules() && modules.isPresent()
                ? project -> isChild(project) && !modules.get().describes(project)
                : project -> false;
            reactivations(unit, belonging, archiving, excluded).forEach(project -> changes.add(new Change(project, Reason.REPOSITORY_ACTIVE)));
        }
        return List.copyOf(changes);
    }

    /**
     * @return the active children belonging to the repository, that aren't described by its configuration and can be archived
     */
    private static List<DependencyTrackProject> obsoleteModules(HousekeepingUnit unit, List<DependencyTrackProject> belonging, ConfiguredModules modules) {
        var obsolete = new LinkedHashMap<String, DependencyTrackProject>();
        belonging.stream()
            .filter(DependencyTrackProject::active)
            .filter(ProjectStatePlanner::isChild)
            .filter(project -> !modules.describes(project))
            .forEach(project -> obsolete.put(project.uuid(), project));

        // a parent can't be archived with active children, that are no obsolete modules themselves, which cascades up the hierarchy
        var active = unit.activeProjects();
        boolean removed;
        do {
            removed = obsolete.values().removeIf(candidate -> active.stream()
                .anyMatch(project -> project.parentUuid().filter(candidate.uuid()::equals).isPresent() && !obsolete.containsKey(project.uuid())));
        } while (removed);
        return List.copyOf(obsolete.values());
    }

    /**
     * @param archiving uuids of the projects that get archived
     * @param excluded  projects that must stay archived
     */
    private static List<DependencyTrackProject> reactivations(HousekeepingUnit unit, List<DependencyTrackProject> belonging, Set<String> archiving,
                                                              Predicate<DependencyTrackProject> excluded) {
        var byName = new LinkedHashMap<String, List<DependencyTrackProject>>();
        belonging.forEach(project -> byName.computeIfAbsent(project.name(), name -> new ArrayList<>()).add(project));

        var candidates = new LinkedHashMap<String, DependencyTrackProject>();
        for (var versions : byName.values()) {
            if (versions.stream().anyMatch(DependencyTrackProject::active)) {
                // the upload archives the other versions of a project, so archived versions next to an active one are expected
                continue;
            }
            if (versions.size() == 1) {
                candidates.put(versions.getFirst().uuid(), versions.getFirst());
            } else {
                versions.stream()
                    .filter(version -> version.lastBomImport().isPresent())
                    .max(Comparator.comparing(version -> version.lastBomImport().orElseThrow()))
                    .ifPresent(latest -> candidates.put(latest.uuid(), latest));
            }
        }
        candidates.values().removeIf(excluded);

        // children are better left archived than active below a parent staying archived, which cascades down the hierarchy
        var archived = unit.projects().stream().filter(project -> !project.active()).map(DependencyTrackProject::uuid).collect(Collectors.toSet());
        boolean removed;
        do {
            removed = candidates.values().removeIf(candidate -> candidate.parentUuid()
                .filter(parent -> archiving.contains(parent) || (archived.contains(parent) && !candidates.containsKey(parent)))
                .isPresent());
        } while (removed);
        return List.copyOf(candidates.values());
    }

    /**
     * @return the projects definitely belonging to the unit's repository, see {@link RepositoryProjectMatcher#select}
     */
    private static List<DependencyTrackProject> belonging(HousekeepingUnit unit) {
        return unit.repository()
            .map(repository -> RepositoryProjectMatcher.select(unit.projects(), repository.name(), repository.gitUrl(), repository.htmlUrl()))
            .orElse(List.of());
    }

    private static boolean isChild(DependencyTrackProject project) {
        return project.parentUuid().isPresent();
    }
}
