package com.mediamarktsaturn.technolinator.housekeeping;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Restricts a housekeeping run to some repositories and Dependency-Track projects. An {@link #all() empty} selection selects everything.
 * <p>
 * Selected repositories and projects are looked up on their own, see {@link TargetedListing}, instead of listing everything.
 * As the {@link HousekeepingWorker} always works on whole {@link HousekeepingUnit}s (a hierarchy of projects must not be split),
 * a unit is selected if <em>any</em> of its repository or projects is, so selecting a project selects its repository and all
 * the projects belonging to it, too.
 * All comparisons are case-insensitive.
 *
 * @param repositories Repositories given as {@code owner/repo}, as the owner is needed to find the installation of the GitHub app.
 *                     Repositories that don't exist anymore are found by the references of their projects.
 * @param projects     Projects given by name (in any version) or UUID
 */
public record HousekeepingSelection(Set<String> repositories, Set<String> projects) {

    private static final HousekeepingSelection ALL = new HousekeepingSelection(Set.of(), Set.of());

    public HousekeepingSelection {
        repositories = normalize(repositories);
        projects = normalize(projects);
    }

    public static HousekeepingSelection all() {
        return ALL;
    }

    public boolean isAll() {
        return repositories.isEmpty() && projects.isEmpty();
    }

    boolean selects(HousekeepingUnit unit) {
        return isAll()
            || !Collections.disjoint(repositories, repositoryKeys(unit))
            || !Collections.disjoint(projects, projectKeys(unit));
    }

    /**
     * @return the repositories and projects of the selection, that none of the [units] belongs to
     */
    Set<String> unmatched(List<HousekeepingUnit> units) {
        var knownRepositories = new HashSet<String>();
        var knownProjects = new HashSet<String>();
        for (var unit : units) {
            knownRepositories.addAll(repositoryKeys(unit));
            knownProjects.addAll(projectKeys(unit));
        }
        var unmatched = new TreeSet<String>();
        repositories.stream().filter(name -> !knownRepositories.contains(name)).forEach(name -> unmatched.add("repository " + name));
        projects.stream().filter(name -> !knownProjects.contains(name)).forEach(name -> unmatched.add("project " + name));
        return unmatched;
    }

    private static Set<String> repositoryKeys(HousekeepingUnit unit) {
        var keys = new HashSet<String>();
        unit.repository().ifPresent(repo -> {
            keys.add(lower(repo.name()));
            keys.add(lower(repo.fullName()));
        });
        if (unit.repository().isEmpty()) {
            unit.projects().stream()
                .flatMap(project -> UnitMatcher.referencesOf(project).stream())
                .forEach(reference -> {
                    keys.add(reference.name());
                    keys.add(reference.owner() + "/" + reference.name());
                });
        }
        return keys;
    }

    private static Set<String> projectKeys(HousekeepingUnit unit) {
        var keys = new HashSet<String>();
        unit.projects().forEach(project -> {
            keys.add(lower(project.uuid()));
            keys.add(lower(project.name()));
        });
        return keys;
    }

    private static Set<String> normalize(Collection<String> values) {
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> lower(value.trim()))
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
