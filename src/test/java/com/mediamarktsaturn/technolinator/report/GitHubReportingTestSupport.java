package com.mediamarktsaturn.technolinator.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PullRequestEvent;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient.SBOMGenerationResult;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.SbomqsClient;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilityReporting;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.cyclonedx.model.Bom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test harness for the GitHub side of the failure reporting.
 * <p>
 * The real {@link AnalysisProcessHandler} and {@link FailureReporter} work against a real GitHub client, which talks to
 * a {@link FakeGitHubServer}. Everything else is replaced: checking out the repository, creating SBOMs with cdxgen,
 * scoring, uploading and the vulnerability report. Tests script what the SBOM creation yields, and look at what ended
 * up in the issues and comments of the fake GitHub.
 */
abstract class GitHubReportingTestSupport {

    static final String BOT = FakeGitHubServer.BOT;
    static final String SECRET = "tok3n-very-secret-value";
    static final String REPORT_TEXT = "## Vulnerability report\n\nNo vulnerabilities, see DEPENDENCY_TRACK_URL";

    @TempDir
    Path tmp;

    FakeGitHubServer github;
    SimpleMeterRegistry meters;
    FailureReporter failureReporter;
    AnalysisProcessHandler handler;

    private GitHub client;
    private RepositoryService repoService;
    private CdxgenClient cdxgen;
    private DependencyTrackClient dtrack;
    private SbomqsClient sbomqs;
    private VulnerabilityReporting vulnerabilityReporting;

    private Result<LocalRepository> checkoutResult;
    private Result<Project> uploadResult = Result.success(Project.none());
    private final AtomicInteger commitCounter = new AtomicInteger();

    @BeforeEach
    void setUpGitHubReporting() throws Exception {
        github = new FakeGitHubServer();
        client = new GitHubBuilder().withEndpoint(github.endpoint()).build();
        meters = new SimpleMeterRegistry();

        repoService = mock(RepositoryService.class);
        cdxgen = mock(CdxgenClient.class);
        dtrack = mock(DependencyTrackClient.class);
        sbomqs = mock(SbomqsClient.class);
        vulnerabilityReporting = mock(VulnerabilityReporting.class);

        var checkout = mock(RepositoryService.CheckoutCommand.class);
        when(repoService.createCheckoutCommand(any(), anyString())).thenReturn(checkout);
        when(checkout.execute(any())).thenAnswer(invocation -> Uni.createFrom().item(checkoutResult()));
        when(repoService.getRepositoryDetails(any())).thenReturn(new RepositoryDetails(
            "shop", "main", "The shop", "https://github.com/acme/shop", "git://github.com/acme/shop.git", List.of()));

        when(dtrack.uploadSBOM(any(), any(), anyString(), any(), any())).thenAnswer(invocation -> Uni.createFrom().item(uploadResult));
        when(dtrack.createOrUpdateParentProject(any(), any())).thenAnswer(invocation -> Uni.createFrom().item(Result.success(Project.none())));
        when(sbomqs.calculateQualityScore(any())).thenReturn(Uni.createFrom().item(Result.failure(new IllegalStateException("no sbomqs"))));
        when(vulnerabilityReporting.createVulnerabilityReport(any(), anyString())).thenAnswer(invocation ->
            Uni.createFrom().item(Result.success(VulnerabilityReporting.VulnerabilityReport.report(REPORT_TEXT, invocation.getArgument(1)))));

        sbomCreation(nothingToAnalyse());
        useReporter(FailureReporter.Mode.ON, List.of(), false, 2);
    }

    @AfterEach
    void tearDownGitHubReporting() {
        github.close();
    }

    // ---- setup of the scenario ----

    /**
     * Replaces the reporter, e.g. to change its mode.
     */
    void useReporter(FailureReporter.Mode mode, List<String> repos, boolean logsOnForks, int timeoutsBeforeReport) {
        var botIdentity = mock(BotIdentity.class);
        when(botIdentity.login()).thenReturn(Optional.of(BOT));
        failureReporter = new FailureReporter(new StatusIssueService(), new PrStatusCommentService(), new SecretRedactor(List.of(SECRET)),
            botIdentity, meters, mode, repos, logsOnForks, timeoutsBeforeReport, 1);
        handler = new AnalysisProcessHandler(repoService, cdxgen, dtrack, sbomqs, vulnerabilityReporting, failureReporter,
            new ObjectMapper(), false, false, "https://dtrack.example.com", true, "");
    }

    /**
     * Scripts the results of the SBOM creation, one per module of the repository, for all following analyses.
     */
    @SafeVarargs
    final void sbomCreation(Result<SBOMGenerationResult>... resultsPerModule) {
        var commands = Arrays.stream(resultsPerModule).map(result -> {
            var command = mock(CdxgenClient.SbomCreationCommand.class);
            when(command.execute(any())).thenReturn(Uni.createFrom().item(result));
            return command;
        }).toList();
        when(cdxgen.createCommands(any(), anyString(), anyBoolean(), any())).thenReturn(commands);
    }

    void checkoutFails(Throwable cause) {
        checkoutResult = Result.failure(cause);
    }

    void checkoutWorks() {
        checkoutResult = null;
    }

    void uploadFails(Throwable cause) {
        uploadResult = Result.failure(cause);
    }

    void uploadWorks() {
        uploadResult = Result.success(Project.none());
    }

    private Result<LocalRepository> checkoutResult() throws IOException {
        return checkoutResult != null ? checkoutResult : Result.success(new LocalRepository(Files.createTempDirectory(tmp, "checkout")));
    }

    // ---- scripted results of the SBOM creation ----

    static Result<SBOMGenerationResult> nothingToAnalyse() {
        return Result.success(SBOMGenerationResult.none());
    }

    /**
     * An SBOM which is created, so the vulnerability report and the upload are involved.
     */
    Result<SBOMGenerationResult> sbomCreated(String project) {
        return Result.success(new SBOMGenerationResult.Yield(new Bom(), List.of(), tmp.resolve("bom.json"), project));
    }

    /**
     * cdxgen failed, with the given log, which is what the report is made of.
     */
    static Result<SBOMGenerationResult> cdxgenFails(String project, String... logLines) {
        return Result.failure(new ProcessFailedException("cdxgen --spec-version 1.7 -o bom.json --project-name " + project, 1, List.of(logLines), project));
    }

    static Result<SBOMGenerationResult> mavenCannotResolve(String project) {
        return cdxgenFails(project,
            "[INFO] Scanning for projects...",
            "[ERROR] Failed to execute goal on project " + project + ": Could not resolve dependencies for project acme:" + project,
            "[ERROR] Authorization: Bearer " + SECRET);
    }

    static Result<SBOMGenerationResult> gradleCannotResolve(String project) {
        return cdxgenFails(project,
            "> Task :" + project + ":compileClasspath FAILED",
            "FAILURE: Build failed with an exception.",
            "Could not resolve all files for configuration ':compileClasspath'.");
    }

    // ---- events ----

    GHRepository freshRepository() throws IOException {
        return client.getRepository(FakeGitHubServer.FULL_NAME);
    }

    String nextCommit() {
        return "c0ffee%02d".formatted(commitCounter.incrementAndGet());
    }

    static Command.Metadata metadata(String ref, String sha) {
        return new Command.Metadata(ref, FakeGitHubServer.FULL_NAME, "trace-" + sha, Optional.of(sha));
    }

    /**
     * Analyses a new commit on the default branch.
     */
    Result<Project> pushToDefaultBranch() throws IOException {
        return pushTo("refs/heads/main", nextCommit(), Optional.empty());
    }

    Result<Project> pushToDefaultBranch(TechnolinatorConfig config) throws IOException {
        return pushTo("refs/heads/main", nextCommit(), Optional.of(config));
    }

    Result<Project> pushTo(String ref, String sha, Optional<TechnolinatorConfig> config) throws IOException {
        var payload = mock(GHEventPayload.Push.class);
        when(payload.getRepository()).thenReturn(freshRepository());
        when(payload.getRef()).thenReturn(ref);
        when(payload.getHead()).thenReturn(sha);
        return await(handler.onPush(new PushEvent(payload, config), metadata(ref, sha)));
    }

    /**
     * Analyses the current state of an open pull-request.
     */
    Result<Project> analysePullRequest(FakeGitHubServer.Pull pull) throws IOException {
        return analysePullRequest(pull, Optional.empty());
    }

    Result<Project> analysePullRequest(FakeGitHubServer.Pull pull, Optional<TechnolinatorConfig> config) throws IOException {
        var repo = freshRepository();
        var payload = mock(GHEventPayload.PullRequest.class);
        when(payload.getRepository()).thenReturn(repo);
        when(payload.getPullRequest()).thenReturn(repo.getPullRequest(pull.number));
        when(payload.getNumber()).thenReturn(pull.number);
        var ref = "refs/heads/" + pull.headRef;
        return await(handler.onPullRequest(new PullRequestEvent(payload, config), metadata(ref, pull.headSha)));
    }

    /**
     * The context of an analysis which is started right now, as the dispatchers build it for timeouts.
     */
    FailureReporter.Context context(String ref, String sha) throws IOException {
        return new FailureReporter.Context(freshRepository(), metadata(ref, sha), Instant.now(), Optional.empty());
    }

    static TechnolinatorConfig configWithReporting(Boolean issues, Boolean pullRequestComments) {
        return new TechnolinatorConfig(null, null, null, null, null, null, null, null, null,
            new TechnolinatorConfig.ReportingConfig(issues, pullRequestComments));
    }
}
