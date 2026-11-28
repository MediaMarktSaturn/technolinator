package com.mediamarktsaturn.technolinator.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.Event;
import com.mediamarktsaturn.technolinator.events.PullRequestEvent;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.events.ReleaseEvent;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.report.FailureReporter;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.SbomqsClient;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilityReporting;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary;
import io.quarkiverse.githubapp.runtime.UtilsProducer;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class AnalysisProcessHandler {

    private static final String SBOM_QUALITY_TAG = "sbom-quality-score=%s";
    private static final String DTRACK_PLACEHOLDER = "DEPENDENCY_TRACK_URL";
    private static final String DTRACK_PROJECT_SEARCH_TMPL = "%s/projects?searchText=%s";
    private static final String BACKSTAGE_CATALOG_INFO = "catalog-info.yaml";

    private static final ConcurrentHashMap<String, Instant> TASKS = new ConcurrentHashMap<>();

    private final DependencyTrackClient dtrackClient;
    private final SbomqsClient sbomqsClient;
    private final VulnerabilityReporting reporter;
    private final FailureReporter failureReporter;

    private final RepositoryService repoService;
    private final CdxgenClient cdxgenClient;
    private final String dtrackUrl;
    private final boolean pushFetchLicenses, pullRequestFetchLicenses, pullRequestReportsEnabled;
    /**
     * Whether the vulnerabilities of pull-requests are summed up, which their commit status and review tell about.
     */
    private final boolean vulnerabilityCheckEnabled;
    private final Pattern backstageCatalogPattern;

    private final ObjectMapper yamlMapper;

    public AnalysisProcessHandler(
        RepositoryService repoService,
        CdxgenClient cdxgenClient,
        DependencyTrackClient dtrackClient,
        SbomqsClient sbomqsClient,
        VulnerabilityReporting reporter,
        FailureReporter failureReporter,
        @UtilsProducer.Yaml
        ObjectMapper yamlMapper,
        @ConfigProperty(name = "app.analysis.cdxgen.fetch_licenses")
        boolean pushFetchLicenses,
        @ConfigProperty(name = "app.pull_requests.cdxgen.fetch_licenses")
        boolean pullRequestFetchLicenses,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl,
        @ConfigProperty(name = "app.pull_requests.enabled")
        boolean pullRequestReportsEnabled,
        @ConfigProperty(name = "app.repo.backstage_annotation_regex")
        String backstageAnnotationRegex,
        @ConfigProperty(name = "app.commit_status_write.enabled")
        boolean commitStatusWriteEnabled,
        @ConfigProperty(name = "app.pull_requests.request_changes.enabled")
        boolean requestChangesEnabled,
        @ConfigProperty(name = "app.commit_status.vulnerability_threshold")
        String vulnerabilityThreshold) {
        this.repoService = repoService;
        this.cdxgenClient = cdxgenClient;
        this.dtrackClient = dtrackClient;
        this.sbomqsClient = sbomqsClient;
        this.yamlMapper = yamlMapper;
        this.reporter = reporter;
        this.failureReporter = failureReporter;
        this.pushFetchLicenses = pushFetchLicenses;
        this.pullRequestFetchLicenses = pullRequestFetchLicenses;
        this.dtrackUrl = dtrackUrl;
        this.pullRequestReportsEnabled = pullRequestReportsEnabled;
        this.backstageCatalogPattern = compileBackstageAnnotationPattern(backstageAnnotationRegex);
        this.vulnerabilityCheckEnabled = (commitStatusWriteEnabled || requestChangesEnabled)
            && VulnerabilitySummary.Severity.parseThreshold(vulnerabilityThreshold).isPresent();
    }

    private static Pattern compileBackstageAnnotationPattern(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (Exception e) {
            Log.errorf(e, "Failed to compile backstage annotation regex '%s'", regex);
            return null;
        }
    }

    /**
     * @return the outcome of the analysis, where the vulnerabilities are told if they are asked for by the commit status
     */
    public Uni<AnalysisOutcome> onPullRequest(PullRequestEvent event, Command.Metadata metadata) {
        var runningSince = TASKS.get(metadata.uniqueId());
        if (runningSince == null) {
            return handleAnalysis(event, metadata, pullRequestFetchLicenses);
        } else {
            Log.infof("Analysis for repo %s, ref %s, commit %s already running since %s, ignoring PR event.",
                event.repoUrl(), event.ref(), metadata.commitSha().orElse("-"), runningSince);
            return Uni.createFrom().item(AnalysisOutcome.withoutVulnerabilities(Result.success(Project.none())));
        }
    }

    public Uni<Result<Project>> onPush(PushEvent event, Command.Metadata metadata) {
        var taskKey = metadata.uniqueId();
        TASKS.putIfAbsent(taskKey, Instant.now());
        // deferred, so that a failure while setting up the analysis can't leave the task registered forever
        return Uni.createFrom().deferred(() -> handleAnalysis(event, metadata, pushFetchLicenses))
            .map(AnalysisOutcome::project)
            .onTermination().invoke(() -> TASKS.remove(taskKey));
    }

    /**
     * @return whether a push analysis of the same repository, ref and commit as described by [metadata] is in progress
     */
    public boolean isPushAnalysisRunning(Command.Metadata metadata) {
        return TASKS.containsKey(metadata.uniqueId());
    }

    public Uni<Result<Project>> onRelease(ReleaseEvent event, Command.Metadata metadata) {
        var repoDetails = repoService.getRepositoryDetails(event);
        return dtrackClient.appendVersionInfo(repoDetails, event.ref(), metadata.commitSha());
    }

    /**
     * Archives all Dependency-Track projects of the given repository, returns the number of archived projects.
     */
    public Uni<Integer> onRepositoryArchived(GHRepository repo) {
        return dtrackClient.archiveProjectsOfRepository(
            repo.getName(), repo.getGitTransportUrl(), repo.getHtmlUrl().toString());
    }

    /**
     * Renames all Dependency-Track projects of the repository's former name to the current name.
     * Projects which can't be renamed are archived instead.
     */
    public Uni<DependencyTrackClient.RenameResult> onRepositoryRenamed(GHRepository repo, String oldName) {
        final var newName = repo.getName();
        final var newVcsUrl = repo.getGitTransportUrl();
        final var newWebsiteUrl = repo.getHtmlUrl().toString();
        return dtrackClient.renameProjectsOfRepository(
            oldName, newName,
            replaceLastSegment(newVcsUrl, newName, oldName),
            replaceLastSegment(newWebsiteUrl, newName, oldName),
            newVcsUrl, newWebsiteUrl);
    }

    private static String replaceLastSegment(String url, String newName, String oldName) {
        final var needle = "/" + newName;
        final var idx = url.lastIndexOf(needle);
        if (idx < 0) {
            return url;
        }
        return url.substring(0, idx) + "/" + oldName + url.substring(idx + needle.length());
    }

    Uni<AnalysisOutcome> handleAnalysis(Event event, Command.Metadata metadata, boolean fetchLicenses) {
        var started = Instant.now();
        var repoDetails = repoService.getRepositoryDetails(event);
        return checkoutAndGenerateSBOMs(event, metadata, fetchLicenses)
            .chain(result -> {
                var sbomResults = result.getItem1();
                var checkoutFailed = result.getItem2() == null;
                // the reports are needed for the pull-request comments as well as for the commit status, but are created once at most
                var reports = createReports(event, sbomResults, metadata).memoize().indefinitely();
                return handlePullRequests(event, sbomResults, reports, metadata, started, checkoutFailed)
                    .chain(() -> handleVulnerabilities(event, sbomResults, reports, metadata))
                    .chain(vulnerabilities -> handleDependencyTrack(event, addBackstageManifestTags(repoDetails, result.getItem2()), sbomResults, metadata)
                        .call(uploadResult -> reportDefaultBranchAnalysis(event, metadata, started, sbomResults, checkoutFailed, uploadResult))
                        .map(uploadResult -> new AnalysisOutcome(uploadResult, vulnerabilities)))
                    .onTermination().invoke(() -> {
                        // there is nothing to clean up if the checkout failed
                        if (result.getItem2() != null) {
                            result.getItem2().close();
                        }
                    });
            });
    }

    /**
     * Sums up the vulnerabilities of the SBOMs of a pull-request, which its commit status tells about.
     * They are never a reason for the analysis itself to fail.
     *
     * @return empty if the vulnerabilities were not looked at, a failure if they can't be determined (e.g. because the SBOM creation failed)
     */
    Uni<Optional<Result<VulnerabilitySummary>>> handleVulnerabilities(Event event, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults,
                                                                       Uni<List<Result<VulnerabilityReporting.VulnerabilityReport>>> reports,
                                                                       Command.Metadata metadata) {
        if (!vulnerabilityCheckEnabled || !(event instanceof PullRequestEvent)) {
            return Uni.createFrom().item(Optional.empty());
        }
        return reports
            .map(reportResults -> summarize(sbomResults, reportResults))
            .onFailure().recoverWithItem(Result::failure)
            .invoke(summary -> {
                metadata.writeToMDC();
                switch (summary) {
                    case Result.Success<VulnerabilitySummary> s ->
                        Log.infof("Vulnerabilities of repo %s, ref %s: %s", event.repoUrl(), metadata.gitRef(), s.result().describe());
                    case Result.Failure<VulnerabilitySummary> f ->
                        Log.warnf(f.cause(), "Vulnerabilities of repo %s, ref %s are unknown", event.repoUrl(), metadata.gitRef());
                }
            })
            .map(Optional::of);
    }

    /**
     * Sums up the vulnerabilities of all SBOMs, which fails if they can't be determined for any of them.
     *
     * @param reports the vulnerability reports of the SBOMs, in the same order
     */
    static Result<VulnerabilitySummary> summarize(List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults,
                                                  List<Result<VulnerabilityReporting.VulnerabilityReport>> reports) {
        var summary = VulnerabilitySummary.NONE;
        for (var i = 0; i < sbomResults.size(); i++) {
            var sbomResult = sbomResults.get(i);
            if (sbomResult instanceof Result.Failure<CdxgenClient.SBOMGenerationResult> f) {
                return Result.failure(f.cause());
            }
            // there is nothing to look at without an SBOM
            if (sbomResult instanceof Result.Success<CdxgenClient.SBOMGenerationResult>(var generated) && generated instanceof CdxgenClient.SBOMGenerationResult.None) {
                continue;
            }
            switch (toVulnerabilitySummary(reports.get(i))) {
                case Result.Success<VulnerabilitySummary> s -> summary = summary.plus(s.result());
                case Result.Failure<VulnerabilitySummary> f -> {
                    return Result.failure(f.cause());
                }
            }
        }
        return Result.success(summary);
    }

    static Result<VulnerabilitySummary> toVulnerabilitySummary(Result<VulnerabilityReporting.VulnerabilityReport> reportResult) {
        return switch (reportResult) {
            case Result.Failure<VulnerabilityReporting.VulnerabilityReport> f -> Result.failure(f.cause());
            case Result.Success<VulnerabilityReporting.VulnerabilityReport> s -> switch (s.result()) {
                case VulnerabilityReporting.VulnerabilityReport.None n ->
                    Result.failure(new IllegalStateException("There's no vulnerability report available"));
                case VulnerabilityReporting.VulnerabilityReport.Report r ->
                    VulnerabilitySummary.fromReport(r.text())
                        .map(Result::success)
                        .orElseGet(() -> VulnerabilityReporting.hasFindings(r.text())
                            ? Result.failure(new IllegalStateException("The vulnerability report doesn't tell the severities of its findings"))
                            : Result.success(VulnerabilitySummary.NONE));
            };
        };
    }

    /**
     * Tells the status issue of the repository how the analysis of its default branch went.
     */
    Uni<Void> reportDefaultBranchAnalysis(Event event, Command.Metadata metadata, Instant started,
                                          List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, boolean checkoutFailed,
                                          Result<Project> uploadResult) {
        if (!(event instanceof PushEvent) || !event.isDefaultBranch()) {
            return Uni.createFrom().voidItem();
        }
        var ctx = new FailureReporter.Context(event.repository(), metadata, started, event.config());
        return failureReporter.onDefaultBranchAnalysis(ctx, sbomResults, checkoutFailed, uploadResult);
    }

    RepositoryDetails addBackstageManifestTags(RepositoryDetails repoDetails, LocalRepository repo) {
        // there is no repository to read from if the checkout failed
        if (backstageCatalogPattern == null || repo == null) {
            return repoDetails;
        }

        try {
            var catalogInfo = repo.dir().resolve(BACKSTAGE_CATALOG_INFO);
            if (Files.isReadable(catalogInfo)) {
                var ciAnnotations = yamlMapper.readTree(catalogInfo.toFile()).path("metadata").path("annotations");
                if (ciAnnotations.isObject()) {
                    var backstageTopics = new ArrayList<String>();
                    ciAnnotations.fieldNames().forEachRemaining(f -> {
                        if (backstageCatalogPattern.matcher(f).matches()) {
                            backstageTopics.add("%s=%s".formatted(f, ciAnnotations.get(f).asText()));
                        }
                    });
                    return repoDetails.withAdditionalTopics(backstageTopics.toArray(String[]::new));
                }
            }
        } catch (Exception e) {
            Log.warnf(e, "Failed to read backstage manifest of repo %s", repoDetails.name());
        }
        return repoDetails;
    }

    Uni<Result<Project>> handleDependencyTrack(Event event, RepositoryDetails repoDetails, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata) {
        if (event.isDefaultBranch()) {
            return Uni.createFrom().deferred(() -> scoreAndUploadSbomIfApplicable(repoDetails, sbomResults, metadata));
        } else {
            return Uni.createFrom().item(Result.success(Project.none()));
        }
    }

    Uni<Void> handlePullRequests(Event event, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults,
                                 Uni<List<Result<VulnerabilityReporting.VulnerabilityReport>>> reports, Command.Metadata metadata,
                                 Instant started, boolean checkoutFailed) {
        if (!pullRequestReportsEnabled) {
            if (Log.isDebugEnabled()) {
                Log.debug("PR analysis is disabled");
            }
            return Uni.createFrom().voidItem();
        }
        // reporting on pull-requests is a best-effort extra, it must never fail the analysis itself
        return Uni.createFrom().<Void>deferred(() -> {
            // get pull-requests from analyzed head branch
            List<GHPullRequest> pullRequests;
            try {
                pullRequests = event.repository().getPullRequests(GHIssueState.OPEN).stream().filter(pr ->
                        pr.getHead().getRef().equals(event.branch()))
                    .toList();
            } catch (IOException e) {
                Log.errorf(e, "Failed to list pull-requests in repo %s with head %s", event.repoUrl(), event.branch());
                return Uni.createFrom().voidItem();
            }

            if (pullRequests.isEmpty()) {
                Log.infof("No pull-requests open in repo %s with head %s", event.repoUrl(), event.branch());
                return Uni.createFrom().voidItem();
            }

            return reports
                .map(reportResults -> combine(event, reportResults))
                .invoke(reportResult -> {
                    if (reportResult instanceof Result.Success<VulnerabilityReporting.VulnerabilityReport>(var report)) {
                        updatePullRequests(event, pullRequests, report, sbomResults, metadata, started, checkoutFailed);
                    }
                })
                .replaceWithVoid();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onFailure().invoke(failure -> Log.errorf(failure, "Pull-Request handling failed for repo %s, ref %s", event.repoUrl(), event.ref()))
            .onFailure().recoverWithNull();
    }

    private void updatePullRequests(Event event, List<GHPullRequest> pullRequests, VulnerabilityReporting.VulnerabilityReport report,
                                    List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata,
                                    Instant started, boolean checkoutFailed) {
        metadata.writeToMDC();
        String reportText = null;
        if (report instanceof VulnerabilityReporting.VulnerabilityReport.Report(String text, String projectName)) {
            reportText = text.replace(DTRACK_PLACEHOLDER, buildDTrackProjectSearchUrl(projectName));
        } else {
            Log.warnf("No vulnerability report created for repo %s, ref %s", event.repoUrl(), event.ref());
        }
        // the analysis result is part of the comment even if there is no report, e.g. to tell about a failure
        var ctx = new FailureReporter.Context(event.repository(), metadata, started, event.config());
        // push events are evaluated for the status issue, once is enough
        var evaluation = event instanceof PullRequestEvent
            ? failureReporter.evaluate(ctx, sbomResults, checkoutFailed, Optional.empty())
            : FailureReporter.Evaluation.inactive();
        for (var pr : pullRequests) {
            failureReporter.updatePullRequest(ctx, pr, evaluation, reportText);
        }
    }

    /**
     * Creates the vulnerability report of every SBOM, in the same order. Fails for the SBOMs which couldn't be created, and has no report for the ones there is nothing to analyse.
     */
    @SuppressWarnings("unchecked")
    Uni<List<Result<VulnerabilityReporting.VulnerabilityReport>>> createReports(Event event, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata) {
        return Uni.createFrom().deferred(() -> {
            metadata.writeToMDC();
            return Uni.combine().all().unis(sbomResults.stream().map(sbomResult ->
                    switch (sbomResult) {
                        case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                            case CdxgenClient.SBOMGenerationResult.Yield y ->
                                reporter.createVulnerabilityReport(y.sbomFile(), y.projectName())
                                    .onFailure().recoverWithItem(Result::failure);
                            case CdxgenClient.SBOMGenerationResult.None n -> {
                                Log.infof("Nothing to analyse in repo %s, ref %s", event.repoUrl(), metadata.gitRef());
                                yield Uni.createFrom().item(Result.success(VulnerabilityReporting.VulnerabilityReport.none()));
                            }
                        };

                        case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                            Log.errorf(f.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), metadata.gitRef());
                            yield Uni.createFrom().item(Result.<VulnerabilityReporting.VulnerabilityReport>failure(f.cause()));
                        }
                    }).toList())
                .with(results -> results.stream()
                    .map(r -> (Result<VulnerabilityReporting.VulnerabilityReport>) r)
                    .toList());
        });
    }

    /**
     * Combines the reports of the SBOMs into the one of the repository, leaving out the ones which couldn't be created
     */
    private static Result<VulnerabilityReporting.VulnerabilityReport> combine(Event event, List<Result<VulnerabilityReporting.VulnerabilityReport>> reportResults) {
        var reports = reportResults.stream().filter(r -> r instanceof Result.Success)
            .map(r -> ((Result.Success<VulnerabilityReporting.VulnerabilityReport>) r).result())
            .filter(r -> r instanceof VulnerabilityReporting.VulnerabilityReport.Report)
            .map(r -> (VulnerabilityReporting.VulnerabilityReport.Report) r)
            .toList();
        return Result.success(VulnerabilityReporting.combine(reports, event.getRepoName()));
    }

    @SuppressWarnings("unchecked")
    Uni<Result<Project>> scoreAndUploadSbomIfApplicable(RepositoryDetails repoDetails, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata) {
        metadata.writeToMDC();
        Uni<Project> parent;
        if (sbomResults.size() > 1) {
            // that's a multi-module project. let's create a parent project to group them
            parent = dtrackClient.createOrUpdateParentProject(repoDetails, metadata.commitSha()).map(result -> {
                if (result instanceof Result.Success<Project> s && s.result() instanceof Project.Available a) {
                    return a;
                }
                return Project.none();
            });
        } else {
            parent = Uni.createFrom().nullItem();
        }
        return parent.chain(parentProject ->
            Uni.combine().all().unis(
                    sbomResults.stream().map(sbomResult ->
                        switch (sbomResult) {
                            case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                                case CdxgenClient.SBOMGenerationResult.Yield y -> {
                                    Log.infof("Got yield for repo %s, ref %s", repoDetails.websiteUrl(), repoDetails.version());
                                    logValidationIssues(repoDetails, y.validationIssues());
                                    // upload sbom even with validationIssues as validation is very strict and most of the issues are tolerated by dependency-track
                                    yield doScoreAndUploadSbom(repoDetails, y.sbom(), y.sbomFile(), y.projectName(), parentProject, metadata.commitSha());
                                }
                                case CdxgenClient.SBOMGenerationResult.None n -> {
                                    Log.infof("Nothing to analyse in repo %s, ref %s", repoDetails.websiteUrl(), repoDetails.version());
                                    yield Uni.createFrom().item(Result.success(Project.none()));
                                }
                            };

                            case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                                Log.errorf(f.cause(), "Analysis failed for repo %s, ref %s", repoDetails.websiteUrl(), repoDetails.version());
                                yield Uni.createFrom().item(Result.failure(f.cause()));
                            }
                        }).toList()).with(Function.identity())
                .map(results -> {
                    var failure = results.stream().filter(r -> r instanceof Result.Failure).findAny();
                    if (failure.isPresent()) {
                        return (Result.Failure<Project>) failure.get();
                    }
                    var projects = results.stream().map(r -> ((Result.Success<Project>) r).result())
                        .filter(p -> p instanceof Project.Available).toList();
                    if (projects.isEmpty()) {
                        return Result.success(Project.none());
                    } else if (projects.size() == 1) {
                        return Result.success(projects.get(0));
                    } else {
                        return Result.success(Project.list(buildDTrackProjectSearchUrl(repoDetails.name())));
                    }
                }));
    }

    Uni<Result<Project>> doScoreAndUploadSbom(RepositoryDetails repoDetails, Bom sbom, Path sbomFile, String projectName, Project parentProject, Optional<String> commitSha) {
        return sbomqsClient.calculateQualityScore(sbomFile)
            .map(result -> switch (result) {
                case Result.Success<SbomqsClient.QualityScore> s -> Optional.of(s.result());
                case Result.Failure<SbomqsClient.QualityScore> f -> Optional.<SbomqsClient.QualityScore>empty();
            }).onFailure().recoverWithItem(Optional::empty)
            .chain(score ->
                dtrackClient.uploadSBOM(
                    addQualityScore(repoDetails, score),
                    sbom, projectName, parentProject, commitSha
                ));
    }


    Uni<Tuple2<List<Result<CdxgenClient.SBOMGenerationResult>>, LocalRepository>> checkoutAndGenerateSBOMs(Event event, Command.Metadata metadata, boolean fetchLicenses) {
        var checkout = Objects.requireNonNull(repoService).createCheckoutCommand(event.repository(), event.ref());
        return checkout.execute(metadata)
            .chain(checkoutResult -> generateSboms(event, checkoutResult, metadata, fetchLicenses));
    }

    Uni<Tuple2<List<Result<CdxgenClient.SBOMGenerationResult>>, LocalRepository>> generateSboms(Event event, Result<LocalRepository> checkoutResult, Command.Metadata metadata, boolean fetchLicenses) {
        metadata.writeToMDC();
        return switch (checkoutResult) {
            case Result.Success<LocalRepository> s -> {
                var localRepo = s.result();
                List<CdxgenClient.SbomCreationCommand> cmds;
                try {
                    cmds = Objects.requireNonNull(cdxgenClient).createCommands(localRepo.dir(), event.getRepoName(), fetchLicenses, event.config(), !event.fromFork());
                } catch (RuntimeException e) {
                    // invalid repository config, the checkout still needs to be handed over for cleanup
                    Log.errorf(e, "Aborting analysis of repo %s, branch %s because of invalid configuration", event.repoUrl(), event.branch());
                    yield Uni.createFrom().item(Tuple2.of(List.of(Result.failure(e)), localRepo));
                }
                yield executeCommands(cmds, metadata).map(result -> Tuple2.of(result, localRepo));
            }
            case Result.Failure<LocalRepository> f -> {
                Log.errorf(f.cause(), "Aborting analysis of repo %s, branch %s because of checkout failure", event.repoUrl(), event.branch());
                yield Uni.createFrom().item(Tuple2.of(List.of(Result.failure(f.cause())), null));
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static Uni<List<Result<CdxgenClient.SBOMGenerationResult>>> executeCommands(List<CdxgenClient.SbomCreationCommand> commands, Command.Metadata metadata) {
        var combined = Uni.combine().all().unis(
            commands.stream().map(cmd -> cmd.execute(metadata)).toList()
        );
        // prepare scripts of the projects may interfere with each other, as they work in the same checkout
        if (commands.stream().anyMatch(cmd -> cmd.prepare() != null)) {
            combined = combined.usingConcurrencyOf(1);
        }
        return combined.with(results -> {
            metadata.writeToMDC();
            Log.infof("Analysis of repo %s, branch %s for %s projects resulted in %s with SBOMs %s",
                metadata.repoFullName(), metadata.gitRef(), commands.size(),
                countByClass(results),
                countByClass(results.stream()
                    .filter(r -> r instanceof Result.Success)
                    .map(r -> ((Result.Success<CdxgenClient.SBOMGenerationResult>) r).result())
                    .toList()));
            return (List<Result<CdxgenClient.SBOMGenerationResult>>) results;
        });
    }

    private static String countByClass(List<?> items) {
        return items.stream().collect(Collectors.groupingBy(Object::getClass, Collectors.counting()))
            .entrySet().stream().map(e -> "%s x %s".formatted(e.getValue(), e.getKey().getSimpleName()))
            .collect(Collectors.joining(", "));
    }

    String buildDTrackProjectSearchUrl(String projectName) {
        return DTRACK_PROJECT_SEARCH_TMPL.formatted(dtrackUrl, projectName);
    }

    static RepositoryDetails addQualityScore(RepositoryDetails repoDetails, Optional<SbomqsClient.QualityScore> score) {
        return score.map(qualityScore ->
            repoDetails.withAdditionalTopics(SBOM_QUALITY_TAG.formatted(qualityScore.score()))
        ).orElse(repoDetails);
    }

    static void logValidationIssues(RepositoryDetails repoDetails, List<ParseException> validationIssues) {
        if (!validationIssues.isEmpty()) {
            Log.warnf("SBOM validation issues for repo %s, ref %s: %s", repoDetails.websiteUrl(), repoDetails.version(),
                validationIssues.stream().map(Throwable::getMessage).collect(Collectors.joining("")));
        }
    }
}
