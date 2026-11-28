package com.mediamarktsaturn.technolinator.sbom;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.Optional;

/**
 * The current metrics of one or more Dependency-Track projects, as far as the commit status tells about them
 *
 * @param critical      Number of critical vulnerabilities
 * @param high          Number of vulnerabilities of high severity
 * @param medium        Number of vulnerabilities of medium severity
 * @param low           Number of vulnerabilities of low severity
 * @param unassigned    Number of vulnerabilities without a severity
 * @param riskScore     The inherited risk score of Dependency-Track
 * @param lastOccurrence When Dependency-Track calculated the metrics (the oldest one if several are combined)
 */
public record ProjectMetrics(
    int critical,
    int high,
    int medium,
    int low,
    int unassigned,
    double riskScore,
    Instant lastOccurrence
) {

    /**
     * @return the metrics, empty if the JSON doesn't contain any, which is the case as long as Dependency-Track didn't calculate them
     */
    static Optional<ProjectMetrics> fromJson(JsonObject json) {
        if (json == null || json.isEmpty() || !(json.getValue("lastOccurrence") instanceof Number lastOccurrence)) {
            return Optional.empty();
        }
        return Optional.of(new ProjectMetrics(
            json.getInteger("critical", 0),
            json.getInteger("high", 0),
            json.getInteger("medium", 0),
            json.getInteger("low", 0),
            json.getInteger("unassigned", 0),
            json.getDouble("inheritedRiskScore", 0.0),
            Instant.ofEpochMilli(lastOccurrence.longValue())
        ));
    }

    /**
     * Sums up the numbers, the result is as old as the oldest of them
     */
    public ProjectMetrics plus(ProjectMetrics other) {
        return new ProjectMetrics(
            critical + other.critical, high + other.high, medium + other.medium, low + other.low, unassigned + other.unassigned,
            riskScore + other.riskScore,
            lastOccurrence.isBefore(other.lastOccurrence) ? lastOccurrence : other.lastOccurrence
        );
    }

    public VulnerabilitySummary vulnerabilities() {
        return new VulnerabilitySummary(critical, high, medium, low, unassigned);
    }
}
