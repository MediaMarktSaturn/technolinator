package com.mediamarktsaturn.technolinator.housekeeping;

/**
 * Outcome of a housekeeping run.
 *
 * @param installations             Number of active (not suspended) installations of the GitHub app found
 * @param failedInstallations       Installations whose repositories couldn't be listed
 * @param repositories              Number of repositories of GitHub the installations grant access to
 * @param projects                  Number of projects (in any state) in Dependency-Track
 * @param unitsWithBoth             Units with a repository and at least one project
 * @param unitsWithoutProjects      Units with a repository, but without projects
 * @param unitsWithoutRepository    Units with projects, but without a repository
 * @param failedUnits               Units where an action failed
 * @param aborted                   The run didn't finish, because the repositories or projects couldn't be listed or it timed out
 */
public record HousekeepingSummary(
    int installations,
    int failedInstallations,
    int repositories,
    int projects,
    int unitsWithBoth,
    int unitsWithoutProjects,
    int unitsWithoutRepository,
    int failedUnits,
    boolean aborted
) {

    /**
     * Whether everything was looked at, so the absence of a repository or project is meaningful
     */
    public boolean complete() {
        return !aborted && failedInstallations == 0 && failedUnits == 0;
    }

    static HousekeepingSummary notStarted() {
        return new HousekeepingSummary(0, 0, 0, 0, 0, 0, 0, 0, true);
    }
}
