package com.mediamarktsaturn.technolinator.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class TestReports {

    static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    static final String REPO = "acme/shop";

    private TestReports() {
    }

    static FailureReport.ToolFailure failure(String project, String errorLine, String excerpt) {
        return new FailureReport.ToolFailure(project, "maven", "cdxgen -o bom.json", 1, "Maven could not resolve dependencies",
            errorLine, "Check the dependency.", excerpt, FailureClassifier.hash(errorLine));
    }

    static FailureReport report(Instant started, String errorLine) {
        return report(started, "sha-" + started.getEpochSecond(), failure("shop", errorLine, "[INFO] build\n[ERROR] " + errorLine));
    }

    static FailureReport report(Instant started, String sha, FailureReport.ToolFailure... failures) {
        return new FailureReport(REPO, "https://github.com/acme/shop", "main", Optional.of(sha), started, "trace01", List.of(failures));
    }
}
