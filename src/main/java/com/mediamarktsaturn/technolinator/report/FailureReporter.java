package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.HttpException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tells the owners of a repository that the analysis of it fails: in a status issue for the default branch,
 * in the comment of the pull-request for pull-requests. See {@link StatusIssueService} and {@link PrStatusCommentService}.
 * <p>
 * Only failures which the owners of the repository can do something about are reported. A broken checkout, an unavailable
 * Dependency-Track or a bug of Technolinator are not, and are also not mistaken for a recovery.
 * <p>
 * Reporting never fails an analysis, problems are logged and counted.
 */
@ApplicationScoped
public class FailureReporter {

    public enum Mode {
        /**
         * Nothing is reported, pull-request comments work as without failure reporting.
         */
        OFF,
        /**
         * Reads from GitHub, but only logs what would be written.
         */
        DRY_RUN,
        /**
         * The default.
         */
        ON;

        static Mode parse(String value) {
            var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (normalized) {
                case "off", "false", "disabled" -> OFF;
                case "on", "true", "enabled", "" -> ON;
                case "dry-run", "dryrun" -> DRY_RUN;
                default -> {
                    Log.warnf("Unknown app.failure_reporting.mode '%s', using dry-run", value);
                    yield DRY_RUN;
                }
            };
        }
    }

    public enum Verdict {
        SUCCESS,
        /**
         * At least one failure the repository owners can act on.
         */
        FAILED,
        /**
         * Failed, but not for a reason the repository owners could fix, or not yet.
         */
        INCONCLUSIVE
    }

    /**
     * @param report only present if the verdict is {@link Verdict#FAILED}
     */
    public record Evaluation(Verdict verdict, FailureReport report) {
        public static Evaluation inactive() {
            return new Evaluation(Verdict.INCONCLUSIVE, null);
        }
    }

    public record Context(GHRepository repo, Command.Metadata metadata, Instant started, Optional<TechnolinatorConfig> config) {

        String branch() {
            var ref = metadata.gitRef();
            return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
        }
    }

    static final long DEFAULT_RETRY_DELAY_MILLIS = 2_000;
    static final int MAX_TRACKED_TIMEOUTS = 1_000;

    private final StatusIssueService issueService;
    private final PrStatusCommentService commentService;
    private final SecretRedactor redactor;
    private final BotIdentity botIdentity;
    private final MeterRegistry meterRegistry;
    private final Mode mode;
    private final List<String> repos;
    private final boolean includeLogsOnForkPullRequests;
    private final int timeoutsBeforeReport;
    private final long retryDelayMillis;

    /**
     * Least recently used first, the oldest entries are dropped, as branches come and go.
     */
    private final Map<String, Integer> consecutiveTimeouts = Collections.synchronizedMap(new LinkedHashMap<String, Integer>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_TRACKED_TIMEOUTS;
        }
    });

    @Inject
    public FailureReporter(
        StatusIssueService issueService,
        PrStatusCommentService commentService,
        SecretRedactor redactor,
        BotIdentity botIdentity,
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.failure_reporting.mode")
        String mode,
        @ConfigProperty(name = "app.failure_reporting.repos")
        List<String> repos,
        @ConfigProperty(name = "app.failure_reporting.include_log_on_fork_prs")
        boolean includeLogsOnForkPullRequests,
        @ConfigProperty(name = "app.failure_reporting.timeouts_before_report")
        int timeoutsBeforeReport
    ) {
        this(issueService, commentService, redactor, botIdentity, meterRegistry, Mode.parse(mode), repos,
            includeLogsOnForkPullRequests, timeoutsBeforeReport, DEFAULT_RETRY_DELAY_MILLIS);
    }

    FailureReporter(
        StatusIssueService issueService,
        PrStatusCommentService commentService,
        SecretRedactor redactor,
        BotIdentity botIdentity,
        MeterRegistry meterRegistry,
        Mode mode,
        List<String> repos,
        boolean includeLogsOnForkPullRequests,
        int timeoutsBeforeReport,
        long retryDelayMillis
    ) {
        this.issueService = issueService;
        this.commentService = commentService;
        this.redactor = redactor;
        this.botIdentity = botIdentity;
        this.meterRegistry = meterRegistry;
        this.mode = mode;
        this.repos = repos.stream().map(String::trim).filter(r -> !r.isEmpty()).toList();
        this.includeLogsOnForkPullRequests = includeLogsOnForkPullRequests;
        this.timeoutsBeforeReport = Math.max(1, timeoutsBeforeReport);
        this.retryDelayMillis = retryDelayMillis;
    }

    // ---- evaluation ----

    /**
     * Decides what the results of an analysis mean for the owners of the repository.
     *
     * @param checkoutFailed whether the failures are the one of the checkout, and not of the SBOM generation
     * @param uploadResult   result of the upload to Dependency-Track, if it is part of the analysis
     */
    public Evaluation evaluate(
        Context ctx,
        List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults,
        boolean checkoutFailed,
        Optional<Result<Project>> uploadResult
    ) {
        if (!isActive(ctx.repo())) {
            return Evaluation.inactive();
        }
        try {
            return doEvaluate(ctx, sbomResults, checkoutFailed, uploadResult);
        } catch (RuntimeException e) {
            countError("evaluate");
            Log.warnf(e, "Could not evaluate failures of repo %s", ctx.metadata().repoFullName());
            return Evaluation.inactive();
        }
    }

    private Evaluation doEvaluate(Context ctx, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, boolean checkoutFailed, Optional<Result<Project>> uploadResult) {
        var causes = new ArrayList<Throwable>();
        var stages = new ArrayList<FailureStage>();
        for (var result : sbomResults) {
            if (result instanceof Result.Failure<CdxgenClient.SBOMGenerationResult> f) {
                causes.add(f.cause());
                stages.add(checkoutFailed ? FailureStage.CHECKOUT : FailureStage.SBOM_GENERATION);
            }
        }
        if (uploadResult.orElse(null) instanceof Result.Failure<Project> f && causes.stream().noneMatch(c -> c == f.cause())) {
            causes.add(f.cause());
            stages.add(FailureStage.UPLOAD);
        }

        if (causes.isEmpty()) {
            consecutiveTimeouts.remove(timeoutKey(ctx));
            return new Evaluation(Verdict.SUCCESS, null);
        }

        var actionable = new ArrayList<FailureReport.ToolFailure>();
        var timeouts = new ArrayList<FailureReport.ToolFailure>();
        for (var i = 0; i < causes.size(); i++) {
            var classification = FailureClassifier.classify(stages.get(i), causes.get(i), redactor::redactLines);
            countFailure(classification);
            switch (classification.kind()) {
                case INFRASTRUCTURE -> {
                    // not reported
                }
                case TIMEOUT -> timeouts.add(toToolFailure(ctx, classification, causes.get(i)));
                case REPO_ACTIONABLE -> actionable.add(toToolFailure(ctx, classification, causes.get(i)));
            }
        }

        if (timeouts.isEmpty()) {
            consecutiveTimeouts.remove(timeoutKey(ctx));
        } else if (timeoutsReachedLimit(ctx)) {
            actionable.addAll(timeouts);
        }

        if (actionable.isEmpty()) {
            Log.infof("Analysis of repo %s, branch %s failed, but not for a reason the repository can fix. Not reporting.",
                ctx.metadata().repoFullName(), ctx.branch());
            return new Evaluation(Verdict.INCONCLUSIVE, null);
        }
        return new Evaluation(Verdict.FAILED, buildReport(ctx, actionable));
    }

    /**
     * Counts a timeout of the branch, a single one is often just load.
     *
     * @return whether the timeouts in a row are enough to report them as failure
     */
    private boolean timeoutsReachedLimit(Context ctx) {
        var inARow = consecutiveTimeouts.merge(timeoutKey(ctx), 1, Integer::sum);
        if (inARow < timeoutsBeforeReport) {
            Log.infof("Analysis of repo %s, branch %s timed out (%s of %s in a row before it is reported)",
                ctx.metadata().repoFullName(), ctx.branch(), inARow, timeoutsBeforeReport);
            return false;
        }
        return true;
    }

    private FailureReport.ToolFailure toToolFailure(Context ctx, FailureClassifier.Classification c, Throwable cause) {
        String project = ctx.metadata().repoFullName();
        String command = "";
        Integer exitCode = null;
        var excerpt = "";
        if (cause instanceof ProcessFailedException process) {
            if (process.projectName() != null && !process.projectName().isBlank()) {
                project = process.projectName();
            }
            command = redactor.redact(process.command());
            exitCode = process.exitCode();
            var lines = LogExcerpter.cleanLines(process.outputLines(), redactor::redactLines);
            excerpt = LogExcerpter.excerpt(lines, StatusReportRenderer.MAX_EXCERPT_LENGTH);
        }
        return new FailureReport.ToolFailure(
            project, c.tool(), command, exitCode,
            c.summary(), redactor.redact(c.errorLine()), c.hint(), excerpt, c.errorHash(), c.kind()
        );
    }

    private static FailureReport buildReport(Context ctx, List<FailureReport.ToolFailure> failures) {
        var repo = ctx.repo();
        return new FailureReport(
            ctx.metadata().repoFullName(),
            repo.getHtmlUrl() == null ? "" : repo.getHtmlUrl().toString(),
            ctx.branch(),
            ctx.metadata().commitSha(),
            ctx.started(),
            ctx.metadata().traceId(),
            List.copyOf(failures)
        );
    }

    /**
     * Evaluates an analysis which did not finish in time.
     */
    public Evaluation evaluateTimeout(Context ctx) {
        if (!isActive(ctx.repo())) {
            return Evaluation.inactive();
        }
        try {
            var timeout = new TimeoutException();
            var classification = FailureClassifier.classify(FailureStage.SBOM_GENERATION, timeout);
            countFailure(classification);
            if (!timeoutsReachedLimit(ctx)) {
                return new Evaluation(Verdict.INCONCLUSIVE, null);
            }
            var failure = toToolFailure(ctx, classification, timeout);
            return new Evaluation(Verdict.FAILED, buildReport(ctx, List.of(failure)));
        } catch (RuntimeException e) {
            countError("evaluate");
            Log.warnf(e, "Could not evaluate timeout of repo %s", ctx.metadata().repoFullName());
            return Evaluation.inactive();
        }
    }

    // ---- default branch: status issue ----

    /**
     * Reports the result of the analysis of the default branch to the status issue.
     */
    public Uni<Void> onDefaultBranchAnalysis(
        Context ctx,
        List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults,
        boolean checkoutFailed,
        Result<Project> uploadResult
    ) {
        return onWorkerPool(ctx, () -> reportToIssue(ctx, evaluate(ctx, sbomResults, checkoutFailed, Optional.ofNullable(uploadResult))));
    }

    /**
     * Reports that the analysis of the default branch did not finish in time.
     */
    public Uni<Void> onDefaultBranchTimeout(Context ctx) {
        return onWorkerPool(ctx, () -> reportToIssue(ctx, evaluateTimeout(ctx)));
    }

    private void reportToIssue(Context ctx, Evaluation evaluation) {
        if (!isActive(ctx.repo()) || !ctx.config().map(TechnolinatorConfig::reporting).map(TechnolinatorConfig.ReportingConfig::issues).orElse(true)) {
            return;
        }
        if (evaluation.verdict() == Verdict.INCONCLUSIVE) {
            return;
        }
        var repoName = ctx.metadata().repoFullName();
        try {
            IssueApi api = issueApi(ctx.repo());
            if (mode == Mode.DRY_RUN) {
                api = new DryRunIssueApi(api, repoName);
            }
            final var issueApi = api;
            var action = switch (evaluation.verdict()) {
                case FAILED -> withRetry(() -> issueService.onFailure(issueApi, evaluation.report()));
                default -> withRetry(() -> issueService.onSuccess(issueApi, repoName, ctx.metadata().commitSha(), ctx.started()));
            };
            countAction("issue", action.name());
        } catch (IOException | RuntimeException e) {
            countError("issue");
            Log.warnf(e, "Could not report the status of repo %s to its status issue", repoName);
        }
    }

    // ---- pull-request: status comment ----

    /**
     * Reports the result of the analysis to the comment of the given pull-request.
     *
     * @param reportText the vulnerability report, null if there is none
     */
    public void updatePullRequest(Context ctx, GHPullRequest pullRequest, Evaluation evaluation, String reportText) {
        var repoName = ctx.metadata().repoFullName();
        try {
            var statusEnabled = statusCommentsEnabled(ctx);
            if (mode == Mode.DRY_RUN && evaluation.verdict() == Verdict.FAILED && isActive(ctx.repo()) && pullRequestCommentsConfigured(ctx)) {
                Log.infof("[dry-run] Would comment the failure of the analysis in pull-request %s#%s", repoName, pullRequest.getNumber());
                if (Log.isDebugEnabled()) {
                    Log.debugf("[dry-run] Comment:%n%s", StatusReportRenderer.pullRequestFailureComment(
                        evaluation.report(), false, reportText, PrStatusCommentService.COMMENT_MARKER));
                }
            }
            var outcome = switch (evaluation.verdict()) {
                case FAILED -> PrStatusCommentService.Outcome.FAILED;
                case SUCCESS -> PrStatusCommentService.Outcome.SUCCEEDED;
                case INCONCLUSIVE -> PrStatusCommentService.Outcome.UNKNOWN;
            };
            var update = new PrStatusCommentService.Update(
                repoName + "#" + pullRequest.getNumber(),
                outcome,
                evaluation.report(),
                reportText,
                statusEnabled && (!isFork(ctx, pullRequest) || includeLogsOnForkPullRequests),
                statusEnabled,
                ctx.started(),
                ctx.metadata().commitSha(),
                statusEnabled ? botIdentity.login() : Optional.empty()
            );
            var api = commentApi(pullRequest);
            var action = withRetry(() -> commentService.update(api, update));
            if (statusEnabled) {
                countAction("pull_request", action.name());
            }
        } catch (IOException | RuntimeException e) {
            countError("pull_request");
            Log.errorf(e, "Failed to comment pull-request %s, repo %s", pullRequest.getNumber(), repoName);
        }
    }

    /**
     * Reports that the analysis of the pull-request did not finish in time.
     */
    public Uni<Void> onPullRequestTimeout(Context ctx, GHPullRequest pullRequest) {
        return onWorkerPool(ctx, () -> {
            var evaluation = evaluateTimeout(ctx);
            if (isActive(ctx.repo())) {
                // the vulnerability report of the previous analysis stays in the comment, it is not touched without a new one
                updatePullRequest(ctx, pullRequest, evaluation, null);
            }
        });
    }

    IssueApi issueApi(GHRepository repo) {
        return new GitHubIssueApi(repo, botIdentity.login());
    }

    CommentApi commentApi(GHPullRequest pullRequest) {
        return new GitHubCommentApi(pullRequest);
    }

    private boolean statusCommentsEnabled(Context ctx) {
        return mode == Mode.ON && isActive(ctx.repo()) && pullRequestCommentsConfigured(ctx);
    }

    private static boolean pullRequestCommentsConfigured(Context ctx) {
        return ctx.config().map(TechnolinatorConfig::reporting).map(TechnolinatorConfig.ReportingConfig::pullRequestComments).orElse(true);
    }

    private boolean isFork(Context ctx, GHPullRequest pullRequest) {
        try {
            var head = pullRequest.getHead().getRepository();
            return head == null || !ctx.repo().getFullName().equalsIgnoreCase(head.getFullName());
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ---- shared ----

    boolean isActive(GHRepository repo) {
        if (mode == Mode.OFF) {
            return false;
        }
        if (repos.isEmpty()) {
            return true;
        }
        return repos.contains(repo.getName()) || repos.contains(repo.getFullName());
    }

    private static String timeoutKey(Context ctx) {
        return ctx.metadata().repoFullName() + "#" + ctx.branch();
    }

    private Uni<Void> onWorkerPool(Context ctx, Runnable action) {
        return Uni.createFrom().<Void>item(() -> {
                ctx.metadata().writeToMDC();
                try {
                    action.run();
                } catch (RuntimeException e) {
                    countError("unexpected");
                    Log.warnf(e, "Failure reporting failed");
                }
                return null;
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onFailure().recoverWithNull();
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    /**
     * All reporting operations can be repeated safely, so a hiccup of GitHub is worth one more try.
     */
    private <T> T withRetry(IoSupplier<T> call) throws IOException {
        try {
            return call.get();
        } catch (IOException | RuntimeException e) {
            if (!isTransient(e)) {
                throw e;
            }
            Log.debugf(e, "Transient GitHub failure, trying once more");
            try {
                Thread.sleep(retryDelayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return call.get();
        }
    }

    static boolean isTransient(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpException http && http.getResponseCode() >= 500) {
                return true;
            }
            var message = t.getMessage();
            if (message != null) {
                var lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("secondary rate limit") || lower.contains("abuse detection")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void countFailure(FailureClassifier.Classification c) {
        meterRegistry.counter("analysis_failures", List.of(
            Tag.of("kind", c.kind().name()),
            Tag.of("tool", c.tool() == null ? "" : c.tool())
        )).increment();
    }

    private void countAction(String target, String action) {
        meterRegistry.counter("failure_report_actions", List.of(Tag.of("target", target), Tag.of("action", action))).increment();
    }

    private void countError(String op) {
        meterRegistry.counter("failure_report_errors", List.of(Tag.of("op", op))).increment();
    }
}
