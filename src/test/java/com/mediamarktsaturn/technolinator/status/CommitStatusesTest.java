package com.mediamarktsaturn.technolinator.status;

import com.mediamarktsaturn.technolinator.sbom.ProjectMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CommitStatusesTest {

    private static final ProjectMetrics METRICS = new ProjectMetrics(1, 2, 3, 4, 0, 41.6, Instant.EPOCH);

    @Test
    void addsMetricsToTheDescription() {
        assertThat(CommitStatuses.withMetrics("SBOM available", METRICS))
            .isEqualTo("SBOM available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low");
    }

    @Test
    void replacesMetricsAlreadyThere() {
        var once = CommitStatuses.withMetrics("SBOMs available", METRICS);
        var other = new ProjectMetrics(0, 0, 0, 0, 7, 0, Instant.EPOCH);

        assertThat(CommitStatuses.withMetrics(once, other))
            .isEqualTo("SBOMs available, risk score 0, vulnerabilities: 0 critical, 0 high, 0 medium, 0 low, 7 unspecified");
        assertThat(CommitStatuses.withoutMetrics(once)).isEqualTo("SBOMs available");
        assertThat(CommitStatuses.withoutMetrics("SBOM available")).isEqualTo("SBOM available");
    }

    @Test
    void limitsTheDescription() {
        assertThat(CommitStatuses.limit("x".repeat(500))).hasSize(CommitStatuses.MAX_DESCRIPTION_LENGTH).endsWith("...");
        assertThat(CommitStatuses.limit("short")).isEqualTo("short");
    }

    @Test
    void recognizesAnalysesMakingSbomsAvailable() {
        assertThat(CommitStatuses.isSbomAvailable("SBOM available")).isTrue();
        assertThat(CommitStatuses.isSbomAvailable("SBOMs available, risk score 1, vulnerabilities: ...")).isTrue();
        assertThat(CommitStatuses.isSbomAvailable("no SBOM available")).isFalse();
        assertThat(CommitStatuses.isSbomAvailable("SBOM creation failed")).isFalse();
        assertThat(CommitStatuses.isSbomAvailable(null)).isFalse();
    }
}
