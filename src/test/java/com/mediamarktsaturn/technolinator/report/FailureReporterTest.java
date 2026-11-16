package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.HttpException;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.report.FailureReporter.Mode;
import static com.mediamarktsaturn.technolinator.report.FailureReporter.Verdict;
import static com.mediamarktsaturn.technolinator.report.TestReports.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FailureReporterTest {

    private static final String SECRET = "tok3n-very-secret-value";

    private final FakeIssueApi issues = new FakeIssueApi();
    private final FakeCommentApi comments = new FakeCommentApi();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private GHRepository repo;
    private GHPullRequest pullRequest;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(GHRepository.class);
        when(repo.getName()).thenReturn("shop");
        when(repo.getFullName()).thenReturn("acme/shop");
        when(repo.getHtmlUrl()).thenReturn(URI.create("https://github.com/acme/shop").toURL());
        pullRequest = mockPullRequest(repo);
    }

    private static GHPullRequest mockPullRequest(GHRepository headRepo) {
        var pr = mock(GHPullRequest.class);
        var head = mock(GHCommitPointer.class);
        when(head.getRepository()).thenReturn(headRepo);
        when(pr.getHead()).thenReturn(head);
        when(pr.getNumber()).thenReturn(7);
        return pr;
    }

    private FailureReporter reporter(Mode mode, List<String> repos, boolean logsOnForks, int timeoutsBeforeReport) {
        var botIdentity = mock(BotIdentity.class);
        when(botIdentity.login()).thenReturn(Optional.of("technolinator[bot]"));
        return new FailureReporter(new StatusIssueService(), new PrStatusCommentService(), new SecretRedactor(List.of(SECRET)),
            botIdentity, meters, mode, repos, logsOnForks, timeoutsBeforeReport, 1) {
            @Override
            IssueApi issueApi(GHRepository r) {
                return issues;
            }

            @Override
            CommentApi commentApi(GHPullRequest pr) {
                return comments;
            }
        };
    }

    private FailureReporter on() {
        return reporter(Mode.ON, List.of(), false, 2);
    }

    private FailureReporter.Context ctx() {
        return ctx(Optional.empty(), T0, "refs/heads/main");
    }

    private FailureReporter.Context ctx(Optional<TechnolinatorConfig> config, Instant started, String ref) {
        return new FailureReporter.Context(repo, new Command.Metadata(ref, "acme/shop", "trace01", Optional.of("abc123")), started, config);
    }

    private static Result<CdxgenClient.SBOMGenerationResult> ok() {
        return Result.success(CdxgenClient.SBOMGenerationResult.none());
    }

    private static Result<CdxgenClient.SBOMGenerationResult> processFailure(String project, String... lines) {
        return Result.failure(new ProcessFailedException("cdxgen -o bom.json", 1, List.of(lines), project));
    }

    private static Result<Project> uploaded() {
        return Result.success(Project.none());
    }

    // ---- evaluation ----

    @Test
    void noFailureIsSuccess() {
        var evaluation = on().evaluate(ctx(), List.of(ok(), ok()), false, Optional.of(uploaded()));

        assertThat(evaluation.verdict()).isEqualTo(Verdict.SUCCESS);
    }

    @Test
    void failingToolIsReportedWithRedactedLogs() {
        var failed = processFailure("backend", "[INFO] start", "[ERROR] Could not resolve dependencies for project backend", "Authorization: Bearer " + SECRET);

        var evaluation = on().evaluate(ctx(), List.of(failed, ok()), false, Optional.empty());

        assertThat(evaluation.verdict()).isEqualTo(Verdict.FAILED);
        var report = evaluation.report();
        assertThat(report.repoFullName()).isEqualTo("acme/shop");
        assertThat(report.repoHtmlUrl()).isEqualTo("https://github.com/acme/shop");
        assertThat(report.branch()).isEqualTo("main");
        assertThat(report.commitSha()).contains("abc123");
        assertThat(report.failures()).singleElement().satisfies(f -> {
            assertThat(f.project()).isEqualTo("backend");
            assertThat(f.tool()).isEqualTo("maven");
            assertThat(f.exitCode()).isEqualTo(1);
            assertThat(f.excerpt()).contains("Could not resolve dependencies").doesNotContain(SECRET);
        });
        assertThat(meters.find("analysis_failures").tag("kind", "REPO_ACTIONABLE").tag("tool", "maven").counter().count()).isEqualTo(1);
    }

    @Test
    void pullRequestBranchNeedsNoPrefixHandling() {
        var evaluation = on().evaluate(ctx(Optional.empty(), T0, "feature/x"), List.of(processFailure("p", "[ERROR] boom")), false, Optional.empty());

        assertThat(evaluation.report().branch()).isEqualTo("feature/x");
    }

    @Test
    void checkoutFailureIsNotReported() {
        var checkout = Result.<CdxgenClient.SBOMGenerationResult>failure(new IOException("git clone failed"));

        var evaluation = on().evaluate(ctx(), List.of(checkout), true, Optional.empty());

        assertThat(evaluation.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(evaluation.report()).isNull();
    }

    @Test
    void uploadFailureIsNotReportedButNotTakenForSuccessEither() {
        var upload = Result.<Project>failure(new IOException("dtrack down"));

        var evaluation = on().evaluate(ctx(), List.of(ok()), false, Optional.of(upload));

        assertThat(evaluation.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void sbomFailurePassedThroughTheUploadIsNotCountedTwice() {
        var failed = processFailure("backend", "[ERROR] Could not resolve dependencies");
        var upload = Result.<Project>failure(((Result.Failure<?>) failed).cause());

        var evaluation = on().evaluate(ctx(), List.of(failed), false, Optional.of(upload));

        assertThat(evaluation.verdict()).isEqualTo(Verdict.FAILED);
        assertThat(evaluation.report().failures()).hasSize(1);
    }

    @Test
    void unknownExceptionsAreNotReported() {
        var evaluation = on().evaluate(ctx(), List.of(Result.failure(new IllegalStateException("bug"))), false, Optional.empty());

        assertThat(evaluation.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void infrastructureFailuresAreNotShownNextToActionableOnes() {
        var evaluation = on().evaluate(ctx(), List.of(processFailure("a", "[ERROR] Could not resolve dependencies"), Result.failure(new IllegalStateException("bug"))),
            false, Optional.empty());

        assertThat(evaluation.verdict()).isEqualTo(Verdict.FAILED);
        assertThat(evaluation.report().failures()).hasSize(1);
    }

    @Test
    void offModeEvaluatesNothing() {
        var evaluation = reporter(Mode.OFF, List.of(), false, 2).evaluate(ctx(), List.of(processFailure("a", "[ERROR] boom")), false, Optional.empty());

        assertThat(evaluation.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(meters.find("analysis_failures").counter()).isNull();
    }

    @Test
    void repositoryAllowList() {
        var other = reporter(Mode.ON, List.of("something-else"), false, 2);
        var listed = reporter(Mode.ON, List.of(" shop "), false, 2);

        assertThat(other.isActive(repo)).isFalse();
        assertThat(listed.isActive(repo)).isTrue();
    }

    // ---- timeouts ----

    private static Result<CdxgenClient.SBOMGenerationResult> timeout() {
        return processFailure("backend", "[INFO] building", "ETIMEDOUT while fetching");
    }

    @Test
    void timeoutsAreReportedAfterTheConfiguredNumberInARow() {
        var cut = on();

        var first = cut.evaluate(ctx(), List.of(timeout()), false, Optional.empty());
        var second = cut.evaluate(ctx(), List.of(timeout()), false, Optional.empty());

        assertThat(first.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(second.verdict()).isEqualTo(Verdict.FAILED);
        assertThat(second.report().failures().getFirst().summary()).containsIgnoringCase("timeout");
    }

    @Test
    void successOrOtherFailuresResetTheTimeoutCount() {
        var cut = on();

        cut.evaluate(ctx(), List.of(timeout()), false, Optional.empty());
        cut.evaluate(ctx(), List.of(ok()), false, Optional.empty());
        var afterSuccess = cut.evaluate(ctx(), List.of(timeout()), false, Optional.empty());
        cut.evaluate(ctx(), List.of(processFailure("a", "[ERROR] Could not resolve dependencies")), false, Optional.empty());
        var afterOtherFailure = cut.evaluate(ctx(), List.of(timeout()), false, Optional.empty());

        assertThat(afterSuccess.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(afterOtherFailure.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void analysisTimeoutCountsLikeTimeoutOfATool() {
        var cut = on();

        var first = cut.evaluateTimeout(ctx());
        var second = cut.evaluateTimeout(ctx());

        assertThat(first.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(second.verdict()).isEqualTo(Verdict.FAILED);
        assertThat(second.report().failures()).singleElement().satisfies(f -> assertThat(f.project()).isEqualTo("acme/shop"));
    }

    // ---- default branch ----

    @Test
    void defaultBranchFailureAndRecoveryDriveTheStatusIssue() {
        var cut = on();

        cut.onDefaultBranchAnalysis(ctx(), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies")), false, null).await().indefinitely();
        assertThat(issues.openCount()).isEqualTo(1);
        assertThat(meters.find("failure_report_actions").tag("target", "issue").tag("action", "CREATED").counter().count()).isEqualTo(1);

        cut.onDefaultBranchAnalysis(ctx(Optional.empty(), T0.plusSeconds(60), "refs/heads/main"), List.of(ok()), false, uploaded()).await().indefinitely();
        assertThat(issues.openCount()).isZero();
        assertThat(issues.issues).hasSize(1);
    }

    @Test
    void inconclusiveResultTouchesNoIssue() {
        var cut = on();
        cut.onDefaultBranchAnalysis(ctx(), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies")), false, null).await().indefinitely();
        issues.writes.clear();

        cut.onDefaultBranchAnalysis(ctx(Optional.empty(), T0.plusSeconds(60), "refs/heads/main"),
            List.of(Result.failure(new IOException("clone failed"))), true, null).await().indefinitely();

        assertThat(issues.writes).isEmpty();
        assertThat(issues.openCount()).isEqualTo(1);
    }

    @Test
    void dryRunDoesNotWriteIssues() {
        var cut = reporter(Mode.DRY_RUN, List.of(), false, 2);

        cut.onDefaultBranchAnalysis(ctx(), List.of(processFailure("backend", "[ERROR] boom")), false, null).await().indefinitely();

        assertThat(issues.issues).isEmpty();
        assertThat(issues.writes).isEmpty();
    }

    @Test
    void repositoryCanOptOut() {
        var config = Optional.of(new TechnolinatorConfig(null, null, null, null, null, null, null, null, null,
            new TechnolinatorConfig.ReportingConfig(false, null)));

        on().onDefaultBranchAnalysis(ctx(config, T0, "refs/heads/main"), List.of(processFailure("backend", "[ERROR] boom")), false, null).await().indefinitely();

        assertThat(issues.issues).isEmpty();
    }

    @Test
    void issuesDisabledIsSkippedSilently() {
        issues.issuesEnabled = false;

        on().onDefaultBranchAnalysis(ctx(), List.of(processFailure("backend", "[ERROR] boom")), false, null).await().indefinitely();

        assertThat(issues.issues).isEmpty();
        assertThat(meters.find("failure_report_errors").counter()).isNull();
    }

    @Test
    void gitHubErrorsNeverFailTheAnalysis() {
        issues.failNextWrite = new IOException("GitHub says no");

        on().onDefaultBranchAnalysis(ctx(), List.of(processFailure("backend", "[ERROR] boom")), false, null).await().indefinitely();

        assertThat(meters.find("failure_report_errors").tag("op", "issue").counter().count()).isEqualTo(1);
    }

    @Test
    void transientGitHubErrorsAreRetriedOnce() {
        issues.failNextWrite = new HttpException("Bad Gateway", 502, "Bad Gateway", "https://api.github.com");

        on().onDefaultBranchAnalysis(ctx(), List.of(processFailure("backend", "[ERROR] boom")), false, null).await().indefinitely();

        assertThat(issues.issues).hasSize(1);
        assertThat(meters.find("failure_report_errors").counter()).isNull();
    }

    @Test
    void transientErrorDetection() {
        assertThat(FailureReporter.isTransient(new HttpException("x", 503, "x", "u"))).isTrue();
        assertThat(FailureReporter.isTransient(new HttpException("x", 404, "x", "u"))).isFalse();
        assertThat(FailureReporter.isTransient(new IOException("You have exceeded a secondary rate limit"))).isTrue();
        assertThat(FailureReporter.isTransient(new RuntimeException(new HttpException("x", 500, "x", "u")))).isTrue();
        assertThat(FailureReporter.isTransient(new IOException("nope"))).isFalse();
    }

    @Test
    void defaultBranchTimeoutIsReportedOnTheSecondTime() {
        var cut = on();

        cut.onDefaultBranchTimeout(ctx()).await().indefinitely();
        assertThat(issues.issues).isEmpty();
        cut.onDefaultBranchTimeout(ctx()).await().indefinitely();

        assertThat(issues.issues).hasSize(1);
        assertThat(issues.get(1).body).containsIgnoringCase("did not finish in time");
    }

    // ---- pull-request ----

    private FailureReporter.Context prCtx(Instant started) {
        return ctx(Optional.empty(), started, "feature");
    }

    @Test
    void pullRequestFailureAndRecoveryKeepOneComment() {
        var cut = on();
        var failed = cut.evaluate(prCtx(T0), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies")), false, Optional.empty());
        cut.updatePullRequest(prCtx(T0), pullRequest, failed, "## Report");
        cut.updatePullRequest(prCtx(T0.plusSeconds(60)), pullRequest,
            cut.evaluate(prCtx(T0.plusSeconds(60)), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies")), false, Optional.empty()), null);

        assertThat(comments.comments).hasSize(1);
        assertThat(comments.onlyBody()).contains("could not create the SBOM").contains("Log excerpt");

        var succeeded = cut.evaluate(prCtx(T0.plusSeconds(120)), List.of(ok()), false, Optional.empty());
        cut.updatePullRequest(prCtx(T0.plusSeconds(120)), pullRequest, succeeded, "## Report");

        assertThat(comments.comments).hasSize(1);
        assertThat(comments.onlyBody()).contains("🎉").contains("## Report");
    }

    @Test
    void forkPullRequestsDoNotGetLogs() {
        var forkRepo = mock(GHRepository.class);
        when(forkRepo.getFullName()).thenReturn("mallory/shop");
        var forkPr = mockPullRequest(forkRepo);
        var cut = on();
        var failed = cut.evaluate(prCtx(T0), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies")), false, Optional.empty());

        cut.updatePullRequest(prCtx(T0), forkPr, failed, null);

        assertThat(comments.onlyBody()).doesNotContain("Log excerpt").contains("Logs are not shown");
    }

    @Test
    void forkPullRequestsGetLogsIfConfigured() {
        var forkRepo = mock(GHRepository.class);
        when(forkRepo.getFullName()).thenReturn("mallory/shop");
        var forkPr = mockPullRequest(forkRepo);
        var cut = reporter(Mode.ON, List.of(), true, 2);
        var failed = cut.evaluate(prCtx(T0), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies")), false, Optional.empty());

        cut.updatePullRequest(prCtx(T0), forkPr, failed, null);

        assertThat(comments.onlyBody()).contains("Log excerpt");
    }

    @Test
    void pullRequestWithUnknownHeadRepositoryCountsAsFork() {
        var detached = mockPullRequest(null);
        var cut = on();
        var failed = cut.evaluate(prCtx(T0), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies")), false, Optional.empty());

        cut.updatePullRequest(prCtx(T0), detached, failed, null);

        assertThat(comments.onlyBody()).doesNotContain("Log excerpt");
    }

    @Test
    void offAndDryRunKeepPullRequestCommentsAsBefore() {
        for (var mode : List.of(Mode.OFF, Mode.DRY_RUN)) {
            comments.comments.clear();
            var cut = reporter(mode, List.of(), false, 2);
            var failed = cut.evaluate(prCtx(T0), List.of(processFailure("backend", "[ERROR] Could not resolve dependencies"), ok()), false, Optional.empty());

            cut.updatePullRequest(prCtx(T0), pullRequest, failed, "## Report of the others");

            assertThat(comments.onlyBody()).isEqualTo("## Report of the others\n\n" + PrStatusCommentService.COMMENT_MARKER);
        }
    }

    @Test
    void repositoryCanOptOutOfPullRequestStatus() {
        var config = Optional.of(new TechnolinatorConfig(null, null, null, null, null, null, null, null, null,
            new TechnolinatorConfig.ReportingConfig(null, false)));
        var cut = on();
        var ctx = ctx(config, T0, "feature");
        var failed = cut.evaluate(ctx, List.of(processFailure("backend", "[ERROR] boom")), false, Optional.empty());

        cut.updatePullRequest(ctx, pullRequest, failed, null);

        assertThat(comments.comments).isEmpty();
    }

    @Test
    void pullRequestTimeoutShowsAfterRepetition() {
        var cut = on();

        cut.onPullRequestTimeout(prCtx(T0), pullRequest).await().indefinitely();
        assertThat(comments.comments).isEmpty();
        cut.onPullRequestTimeout(prCtx(T0.plusSeconds(60)), pullRequest).await().indefinitely();

        assertThat(comments.onlyBody()).containsIgnoringCase("did not finish in time");
    }

    @Test
    void modeParsing() {
        assertThat(Mode.parse("on")).isEqualTo(Mode.ON);
        assertThat(Mode.parse("OFF")).isEqualTo(Mode.OFF);
        assertThat(Mode.parse("dry-run")).isEqualTo(Mode.DRY_RUN);
        assertThat(Mode.parse("dry_run")).isEqualTo(Mode.DRY_RUN);
        assertThat(Mode.parse("whatever")).isEqualTo(Mode.DRY_RUN);
        assertThat(Mode.parse("")).isEqualTo(Mode.ON);
        assertThat(Mode.parse(null)).isEqualTo(Mode.ON);
    }
}
