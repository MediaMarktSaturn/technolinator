package com.mediamarktsaturn.technolinator.status;

import com.mediamarktsaturn.technolinator.Commons;
import com.mediamarktsaturn.technolinator.sbom.ProjectMetrics;

/**
 * What the commit statuses of Technolinator look like
 */
public final class CommitStatuses {

    /**
     * The context (name) of the commit statuses
     */
    public static final String CONTEXT = "Supply Chain Security";

    /**
     * GitHub rejects longer descriptions of commit statuses
     */
    public static final int MAX_DESCRIPTION_LENGTH = 140;

    private static final String METRICS_SEPARATOR = ", risk score ";

    private CommitStatuses() {
    }

    /**
     * @return the [description], shortened to what GitHub accepts
     */
    public static String limit(String description) {
        return Commons.truncate(description, MAX_DESCRIPTION_LENGTH, "...");
    }

    /**
     * Adds the metrics to the description of a status, e.g. {@code SBOM available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low}.
     * Metrics already part of the description are replaced.
     */
    public static String withMetrics(String description, ProjectMetrics metrics) {
        return limit("%s%s%d, vulnerabilities: %s".formatted(
            withoutMetrics(description), METRICS_SEPARATOR, Math.round(metrics.riskScore()), metrics.vulnerabilities().describe()));
    }

    /**
     * @return the description, without the metrics added by {@link #withMetrics}
     */
    public static String withoutMetrics(String description) {
        var index = description.indexOf(METRICS_SEPARATOR);
        return index < 0 ? description : description.substring(0, index);
    }

    /**
     * @return whether the description is the one of an analysis of the default branch that succeeded in making SBOMs available
     */
    public static boolean isSbomAvailable(String description) {
        return description != null && (description.startsWith("SBOM available") || description.startsWith("SBOMs available"));
    }
}
