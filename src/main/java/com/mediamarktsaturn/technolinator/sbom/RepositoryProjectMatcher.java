package com.mediamarktsaturn.technolinator.sbom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Decides which Dependency-Track projects belong to a repository, the way Technolinator names and describes the projects it uploads:
 * the project is named like the repository (or [repository]-[module] for modules of multi-module repositories) and references
 * the repository by its VCS and website URL.
 * <p>
 * This is the one and only implementation of that decision, used for reacting to repository events as well as for housekeeping.
 */
public final class RepositoryProjectMatcher {

    private RepositoryProjectMatcher() {
    }

    /**
     * @return true if the name of the project is the name of the repository or starts with it, followed by a dash
     */
    public static boolean matchesRepoName(String projectName, String repoName) {
        return projectName != null && (projectName.equals(repoName) || projectName.startsWith(repoName + "-"));
    }

    /**
     * The names of the repositories a project of the given name could belong to according to {@link #matchesRepoName}:
     * the name itself and every leading part ending before a dash.
     * Allows indexing projects by repository name instead of checking each project against each repository.
     */
    public static List<String> possibleRepoNames(String projectName) {
        var names = new ArrayList<String>();
        if (projectName == null || projectName.isEmpty()) {
            return names;
        }
        names.add(projectName);
        for (int dash = projectName.indexOf('-'); dash >= 0; dash = projectName.indexOf('-', dash + 1)) {
            if (dash > 0) {
                names.add(projectName.substring(0, dash));
            }
        }
        return names;
    }

    /**
     * Selects the projects belonging to the repository. Projects created by technolinator carry external references
     * to the repository, which is the strongest signal. Projects lacking such references (e.g. created by other means
     * or never updated with metadata) are matched by their exact name, or, in case of multi-module children,
     * by being a child of an already matched project. Projects referencing a different repository are never selected.
     *
     * @param candidates the projects to choose from, in any version and state
     * @param repoName   name of the repository, without its owner
     * @param vcsUrl     VCS URL of the repository, like technolinator references it
     * @param websiteUrl website URL of the repository, like technolinator references it
     */
    public static List<DependencyTrackProject> select(List<DependencyTrackProject> candidates, String repoName, String vcsUrl, String websiteUrl) {
        var matched = new ArrayList<DependencyTrackProject>();
        var matchedUuids = new HashSet<String>();
        var remaining = new ArrayList<DependencyTrackProject>();

        for (var candidate : candidates) {
            if (belongsToRepository(candidate, repoName, vcsUrl, websiteUrl)) {
                matched.add(candidate);
                matchedUuids.add(candidate.uuid());
            } else if (matchesRepoName(candidate.name(), repoName) && !referencesOtherRepository(candidate, vcsUrl, websiteUrl)) {
                remaining.add(candidate);
            }
        }

        // adopt children of matched projects, repeat for nested hierarchies until nothing changes
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var it = remaining.iterator(); it.hasNext(); ) {
                var candidate = it.next();
                if (candidate.parentUuid().filter(matchedUuids::contains).isPresent()) {
                    matched.add(candidate);
                    matchedUuids.add(candidate.uuid());
                    it.remove();
                    changed = true;
                }
            }
        }
        return matched;
    }

    /**
     * @return true if the project is directly identified as belonging to the repository: either by an external
     * reference to the repository or, in absence of any repository reference, by its exact name.
     */
    static boolean belongsToRepository(DependencyTrackProject project, String repoName, String vcsUrl, String websiteUrl) {
        if (!matchesRepoName(project.name(), repoName)) {
            return false;
        }
        if (referencesRepository(project, vcsUrl, websiteUrl)) {
            return true;
        }
        return project.repositoryUrls().isEmpty() && repoName.equals(project.name());
    }

    private static boolean referencesRepository(DependencyTrackProject project, String vcsUrl, String websiteUrl) {
        return project.repositoryUrls().stream().anyMatch(url -> url.equalsIgnoreCase(vcsUrl) || url.equalsIgnoreCase(websiteUrl));
    }

    private static boolean referencesOtherRepository(DependencyTrackProject project, String vcsUrl, String websiteUrl) {
        return !project.repositoryUrls().isEmpty() && !referencesRepository(project, vcsUrl, websiteUrl);
    }
}
