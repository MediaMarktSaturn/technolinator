package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.report.TestReports.T0;
import static com.mediamarktsaturn.technolinator.report.TestReports.failure;
import static com.mediamarktsaturn.technolinator.report.TestReports.report;
import static org.assertj.core.api.Assertions.assertThat;

class StatusReportRendererTest {

    private static final String MARKER = "[//]: # (Technolinator)";

    @Test
    void issueBodyShowsEverythingNeeded() {
        var body = StatusReportRenderer.issueBody(report(T0, "Could not resolve foo:bar"), T0.minusSeconds(86_400), List.of("@acme/owners"));

        assertThat(StatusMarker.parse(body)).hasValueSatisfying(m -> {
            assertThat(m.state()).isEqualTo(StatusMarker.State.FAILED);
            assertThat(m.started()).isEqualTo(T0);
        });
        assertThat(body).startsWith("<!-- technolinator:status")
            .contains("SBOM creation is failing on `main`")
            .contains("2026-02-28 10:00 UTC")
            .contains("[`sha-1772")
            .contains("/commit/sha-")
            .contains("trace01")
            .contains("**Key error:** `Could not resolve foo:bar`")
            .contains("**Exit code:** `1`")
            .contains("**How to fix:** Check the dependency.")
            .contains("<summary>Log excerpt</summary>")
            .contains("[ERROR] Could not resolve foo:bar")
            .contains("cc @acme/owners")
            .contains("reporting.issues: false");
    }

    @Test
    void titleIsStable() {
        assertThat(StatusReportRenderer.issueTitle("main")).isEqualTo("Technolinator: SBOM creation fails on main");
    }

    @Test
    void backticksInLogsCannotBreakOutOfTheCodeBlock() {
        var excerpt = "line\n```\n# injected heading\n[x](http://evil)\n````\nend";
        var body = StatusReportRenderer.issueBody(report(T0, "sha", failure("shop", "err with ` and ``` ticks", excerpt)), T0, List.of());

        assertThat(body).contains("`````\n" + excerpt + "\n`````");
        assertThat(body).contains("```` err with ` and ``` ticks ````");
    }

    @Test
    void sizeIsCappedForHugeExcerptsAndManyFailures() {
        var failures = new ArrayList<FailureReport.ToolFailure>();
        for (var i = 0; i < 12; i++) {
            failures.add(failure("module-" + i, "error " + i, "x".repeat(200_000)));
        }
        var report = report(T0, "abc", failures.toArray(FailureReport.ToolFailure[]::new));

        var issue = StatusReportRenderer.issueBody(report, T0, List.of());
        var comment = StatusReportRenderer.pullRequestFailureComment(report, true, "other ".repeat(2000), MARKER);

        assertThat(issue.length()).isLessThan(StatusReportRenderer.MAX_BODY_LENGTH + 5_000).isLessThan(65_536);
        assertThat(comment.length()).isLessThan(65_536);
        assertThat(issue).contains("7 more failing projects not shown").contains("module-0").contains("### `module-4`").doesNotContain("### `module-5`");
        assertThat(comment).endsWith(MARKER);
    }

    @Test
    void otherContentIsKeptIntactAsLongAsTheCommentFits() {
        var other = "vulnerability\n".repeat(3_000);
        var report = report(T0, "abc", failure("shop", "error", "short log"));

        var comment = StatusReportRenderer.pullRequestFailureComment(report, true, other, MARKER);

        assertThat(other.length()).isGreaterThan(StatusReportRenderer.MAX_OTHER_CONTENT_LENGTH);
        assertThat(comment).contains(other.strip()).doesNotContain("The report is truncated").endsWith(MARKER);
        assertThat(comment.length()).isLessThan(65_536);
    }

    @Test
    void hugeOtherContentCannotExceedTheLimitOfGitHub() {
        var other = "vulnerability\n".repeat(100_000);
        var report = report(T0, "abc", failure("shop", "error", "x".repeat(200_000)));

        var failureComment = StatusReportRenderer.pullRequestFailureComment(report, true, other, MARKER);
        var recoveredComment = StatusReportRenderer.pullRequestRecoveredComment(T0, Optional.of("abc"), other, MARKER);

        assertThat(failureComment.length()).isLessThan(65_536);
        assertThat(failureComment).endsWith(MARKER).contains("The report is truncated");
        assertThat(recoveredComment.length()).isLessThan(65_536);
        assertThat(recoveredComment).endsWith(MARKER).contains("The report is truncated");
    }

    @Test
    void hiddenProjectsAreListedLimited() {
        var failures = new ArrayList<FailureReport.ToolFailure>();
        for (var i = 0; i < 30; i++) {
            failures.add(failure("module-" + i, "error " + i, ""));
        }
        var report = report(T0, "abc", failures.toArray(FailureReport.ToolFailure[]::new));

        var body = StatusReportRenderer.issueBody(report, T0, List.of());

        assertThat(body).contains("25 more failing projects not shown").contains("`module-14`, ...").doesNotContain("module-15");
    }

    @Test
    void causeChangedCommentListsLimitedFailures() {
        var failures = new ArrayList<FailureReport.ToolFailure>();
        for (var i = 0; i < 30; i++) {
            failures.add(failure("module-" + i, "error " + i, ""));
        }

        var comment = StatusReportRenderer.issueCauseChangedComment(report(T0, "abc", failures.toArray(FailureReport.ToolFailure[]::new)));

        assertThat(comment).contains("`module-4`").doesNotContain("module-5").contains("- and 25 more");
    }

    @Test
    void titleAndInlineCodeAreLimited() {
        assertThat(StatusReportRenderer.issueTitle("b".repeat(1000)).length()).isEqualTo(StatusReportRenderer.MAX_TITLE_LENGTH);
        assertThat(StatusReportRenderer.code("a\nb\r\nc")).isEqualTo("`a b c`");
        assertThat(StatusReportRenderer.code("x".repeat(5000)).length()).isLessThan(StatusReportRenderer.MAX_INLINE_LENGTH + 20);
    }

    @Test
    void limitCutsAtLineEndsAndKeepsShortContent() {
        assertThat(StatusReportRenderer.limit("short", 100)).isEqualTo("short");

        var limited = StatusReportRenderer.limit("line\n".repeat(1000), 500);

        assertThat(limited.length()).isLessThanOrEqualTo(500);
        assertThat(limited).startsWith("line\nline").contains("The report is truncated");
    }

    @Test
    void forkPullRequestsOmitLogsAndKeyErrorLine() {
        var secretish = "customer-internal-artifact-name";
        var report = report(T0, "sha", failure("shop", "Could not resolve " + secretish, "[ERROR] " + secretish));

        var comment = StatusReportRenderer.pullRequestFailureComment(report, false, null, MARKER);

        assertThat(comment).doesNotContain(secretish).doesNotContain("Log excerpt")
            .contains("Logs are not shown for pull requests from forks")
            .contains("Maven could not resolve dependencies");
    }

    @Test
    void pullRequestFailureCommentKeepsOtherContentAndEndsWithMarker() {
        var comment = StatusReportRenderer.pullRequestFailureComment(report(T0, "boom"), true, "## Vulnerabilities\nnone", MARKER);

        assertThat(comment).startsWith("<!-- technolinator:status").endsWith(MARKER)
            .contains("could not create the SBOM").contains("## Vulnerabilities\nnone");
        assertThat(PrStatusCommentService.isStatusComment(comment)).isTrue();
        assertThat(StatusMarker.parse(comment)).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.FAILED));
    }

    @Test
    void recoveredComment() {
        var comment = StatusReportRenderer.pullRequestRecoveredComment(T0, Optional.of("0123456789abcdef"), "report", MARKER);

        assertThat(comment).contains("🎉").contains("`01234567`").contains("report").endsWith(MARKER);
        assertThat(StatusMarker.parse(comment)).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.RECOVERED));
    }

    @Test
    void resolvedIssueBodyKeepsFailureAndIsMarkedResolved() {
        var failed = StatusReportRenderer.issueBody(report(T0, "boom"), T0, List.of());

        var resolved = StatusReportRenderer.resolvedIssueBody(failed, Instant.parse("2026-03-02T00:00:00Z"), Optional.of("abcdef123456"));

        assertThat(StatusMarker.parse(resolved)).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.RESOLVED));
        assertThat(resolved).contains("Resolved on 2026-03-02 00:00 UTC").contains("`abcdef12`").contains("boom");
        // idempotent
        assertThat(StatusReportRenderer.resolvedIssueBody(resolved, Instant.parse("2026-03-03T00:00:00Z"), Optional.empty())).isEqualTo(resolved);
    }

    @Test
    void markResolvedChangesOnlyTheMarker() {
        var failed = StatusReportRenderer.issueBody(report(T0, "boom"), T0, List.of());

        var resolved = StatusReportRenderer.markResolved(failed);

        assertThat(resolved).isEqualTo(StatusMarker.replace(failed, new StatusMarker(StatusMarker.State.RESOLVED, T0, "sha-" + T0.getEpochSecond(), report(T0, "boom").errorHash())));
        assertThat(StatusReportRenderer.markResolved("no marker")).isEqualTo("no marker");
    }

    @Test
    void causeChangedComment() {
        var comment = StatusReportRenderer.issueCauseChangedComment(report(T0, "new problem"));

        assertThat(comment).contains("cause of the failure changed").contains("`shop`").contains("Maven could not resolve dependencies");
    }
}
