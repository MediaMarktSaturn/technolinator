package com.mediamarktsaturn.technolinator.sbom;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectMetricsTest {

    @Test
    void readsTheMetricsOfDependencyTrack() {
        var json = JsonObject.of("critical", 1, "high", 2, "medium", 3, "low", 4, "unassigned", 5, "vulnerabilities", 15,
            "inheritedRiskScore", 42.5, "lastOccurrence", 1_000L);

        assertThat(ProjectMetrics.fromJson(json)).contains(new ProjectMetrics(1, 2, 3, 4, 5, 42.5, Instant.ofEpochMilli(1_000L)));
    }

    @Test
    void missingNumbersAreZero() {
        assertThat(ProjectMetrics.fromJson(JsonObject.of("lastOccurrence", 1_000L)))
            .contains(new ProjectMetrics(0, 0, 0, 0, 0, 0.0, Instant.ofEpochMilli(1_000L)));
    }

    @Test
    void withoutCalculationThereAreNoMetrics() {
        assertThat(ProjectMetrics.fromJson(null)).isEmpty();
        assertThat(ProjectMetrics.fromJson(new JsonObject())).isEmpty();
        assertThat(ProjectMetrics.fromJson(JsonObject.of("critical", 1))).isEmpty();
    }

    @Test
    void sumsUpAndIsAsOldAsTheOldest() {
        var one = new ProjectMetrics(1, 2, 3, 4, 5, 1.5, Instant.ofEpochMilli(2_000L));
        var other = new ProjectMetrics(10, 20, 30, 40, 50, 2.0, Instant.ofEpochMilli(1_000L));

        assertThat(one.plus(other)).isEqualTo(new ProjectMetrics(11, 22, 33, 44, 55, 3.5, Instant.ofEpochMilli(1_000L)));
        assertThat(one.vulnerabilities()).isEqualTo(new VulnerabilitySummary(1, 2, 3, 4, 5));
    }
}
