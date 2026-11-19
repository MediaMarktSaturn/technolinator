package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.quarkus.logging.Log;

import java.util.Locale;
import java.util.Optional;

/**
 * The one log line for every piece of work a housekeeping action acted on, to be used for monitoring dashboards.
 * All of them are found by searching for {@link #TAG}, and are related to the run by the {@code traceId}.
 * <pre>
 * HOUSEKEEPING_ACTION action=archive_orphaned_projects status=done repository=org/repo repository_state=archived project=0b8a3f1e-... name=repo version=main
 * </pre>
 * One line is written per project that was archived, reactivated or updated, or whose treatment failed. Whatever was skipped
 * (limits, units not applying, projects being up to date already) is not logged, as nothing was acted on.
 * A failure not related to a single project (e.g. a timeout) is written once for the unit, with {@code project=-}.
 * <p>
 * Values are joined by underscores, like for the {@code GITHUB_REPORT}, so that log search splitting words at hyphens can't confuse them.
 * Values that still contain whitespace, quotes or equal signs are quoted.
 */
public final class HousekeepingActionLog {

    public static final String TAG = "HOUSEKEEPING_ACTION";

    public enum Status {
        /**
         * The action was performed
         */
        DONE,
        /**
         * Dry-run mode: the action would have been performed
         */
        DRY_RUN,
        /**
         * The action failed
         */
        FAILED
    }

    private HousekeepingActionLog() {
    }

    /**
     * Records the outcome of an action for a project of the unit
     */
    static void project(String action, Status status, HousekeepingUnit unit, DependencyTrackProject project) {
        write(action, status, unit, Optional.of(project));
    }

    /**
     * Records a successful or planned action for a project of the unit
     */
    static void project(String action, boolean dryRun, HousekeepingUnit unit, DependencyTrackProject project) {
        project(action, dryRun ? Status.DRY_RUN : Status.DONE, unit, project);
    }

    /**
     * Records the failure of an action, that can't be related to a single project of the unit
     */
    static void unitFailed(String action, HousekeepingUnit unit) {
        write(action, Status.FAILED, unit, Optional.empty());
    }

    private static void write(String action, Status status, HousekeepingUnit unit, Optional<DependencyTrackProject> project) {
        var line = "%s action=%s status=%s repository=%s repository_state=%s project=%s name=%s version=%s".formatted(
            TAG,
            snake(action),
            snake(status.name()),
            value(unit.repository().map(GitHubRepo::fullName).orElse(null)),
            snake(unit.repositoryState().name()),
            value(project.map(DependencyTrackProject::uuid).orElse(null)),
            value(project.map(DependencyTrackProject::name).orElse(null)),
            value(project.map(DependencyTrackProject::version).orElse(null))
        );
        if (status == Status.FAILED) {
            Log.warn(line);
        } else {
            Log.info(line);
        }
    }

    private static String snake(String value) {
        return value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String value(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.chars().anyMatch(c -> Character.isWhitespace(c) || c == '"' || c == '=')) {
            return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\s", " ") + '"';
        }
        return value;
    }
}
