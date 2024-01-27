package com.mediamarktsaturn.technolinator.handler;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.Event;
import com.mediamarktsaturn.technolinator.events.PullRequestEvent;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.events.ReleaseEvent;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.SbomqsClient;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilityReporting;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class AnalysisProcessHandler {

    private static final String SBOM_QUALITY_TAG = "sbom-quality-score=%s";
    private static final String COMMENT_MARKER = "[//]: # (Technolinator)";
    private static final String DTRACK_PLACEHOLDER = "DEPENDENCY_TRACK_URL";
    private static final String DTRACK_PROJECT_SEARCH_TMPL = "%s/projects?searchText=%s";

    private static final ConcurrentHashMap<String, Instant> TASKS = new ConcurrentHashMap<>();

    private final DependencyTrackClient dtrackClient;
    private final SbomqsClient sbomqsClient;
    private final VulnerabilityReporting reporter;

    private final RepositoryService repoService;
    private final CdxgenClient cdxgenClient;
    private final String dtrackUrl;
    private final boolean pushFetchLicenses, pullRequestFetchLicenses;

    private final boolean pullRequestReportsEnabled;

    public AnalysisProcessHandler(
        RepositoryService repoService,
        CdxgenClient cdxgenClient,
        DependencyTrackClient dtrackClient,
        SbomqsClient sbomqsClient,
        VulnerabilityReporting reporter,
        @ConfigProperty(name = "app.analysis.cdxgen.fetch_licenses")
        boolean pushFetchLicenses,
        @ConfigProperty(name = "app.pull_requests.cdxgen.fetch_licenses")
        boolean pullRequestFetchLicenses,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl,
        @ConfigProperty(name = "app.pull_requests.enabled")
        boolean pullRequestReportsEnabled) {
        this.repoService = repoService;
        this.cdxgenClient = cdxgenClient;
        this.dtrackClient = dtrackClient;
        this.sbomqsClient = sbomqsClient;
        this.reporter = reporter;
        this.pushFetchLicenses = pushFetchLicenses;
        this.pullRequestFetchLicenses = pullRequestFetchLicenses;
        this.dtrackUrl = dtrackUrl;
        this.pullRequestReportsEnabled = pullRequestReportsEnabled;
    }

    public Uni<Result<Project>> onPullRequest(PullRequestEvent event, Command.Metadata metadata) {
        var runningSince = TASKS.get(metadata.uniqueId());
        if (runningSince == null) {
            return handleAnalysis(event, metadata, pullRequestFetchLicenses);
        } else {
            Log.infof("Analysis for repo %s, ref %s, commit %s already running since %s, ignoring PR event.",
                event.repoUrl(), event.ref(), metadata.commitSha().orElse("-"));
            return Uni.createFrom().item(Result.success(Project.none()));
        }
    }

    public Uni<Result<Project>> onPush(PushEvent event, Command.Metadata metadata) {
        var taskKey = metadata.uniqueId();
        TASKS.computeIfAbsent(taskKey, k -> Instant.now());
        return handleAnalysis(event, metadata, pushFetchLicenses)
            .onTermination().invoke(() -> TASKS.remove(taskKey));
    }

    public Uni<Result<Project>> onRelease(ReleaseEvent event, Command.Metadata metadata) {
        var repoDetails = repoService.getRepositoryDetails(event);
        return dtrackClient.appendVersionInfo(repoDetails, event.ref(), metadata.commitSha());
    }

    Uni<Result<Project>> handleAnalysis(Event<?> event, Command.Metadata metadata, boolean fetchLicenses) {
        var repoDetails = repoService.getRepositoryDetails(event);
        return checkoutAndGenerateSBOMs(event, metadata, fetchLicenses)
            .call(result -> handlePullRequests(event, result.getItem1(), metadata))
            .chain(result -> handleDependencyTrack(event, repoDetails, result.getItem1(), metadata)
                .onTermination().invoke(() -> result.getItem2().close())
            );
    }

    Uni<Result<Project>> handleDependencyTrack(Event<?> event, RepositoryDetails repoDetails, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata) {
        if (event.isDefaultBranch()) {
            return Uni.createFrom().deferred(() -> scoreAndUploadSbomIfApplicable(repoDetails, sbomResults, metadata));
        } else {
            return Uni.createFrom().item(Result.success(Project.none()));
        }
    }

    Uni<Void> handlePullRequests(Event<?> event, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata) {
        if (!pullRequestReportsEnabled) {
            if (Log.isDebugEnabled()) {
                Log.debug("PR analysis is disabled");
            }
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().deferred(() -> {
            // get pull-requests from analyzed head branch
            List<GHPullRequest> pullRequests;
            try {
                pullRequests = event.repository().getPullRequests(GHIssueState.OPEN).stream().filter(pr ->
                        pr.getHead().getRef().equals(event.branch()))
                    .toList();
            } catch (IOException e) {
                Log.errorf("Failed to list pull-requests in repo %s with head %s", event.repoUrl(), event.branch());
                return Uni.createFrom().failure(e);
            }

            if (!pullRequests.isEmpty()) {
                return createReport(event, sbomResults, metadata)
                    .invoke(reportResult -> reportResult.mapSuccess(report -> {
                            metadata.writeToMDC();
                            if (report instanceof VulnerabilityReporting.VulnerabilityReport.Report(
                                String reportText, String projectName
                            )) {
                                pullRequests.forEach(pr -> upsertPullRequestComment(pr, reportText, projectName));
                            } else {
                                Log.warnf("No vulnerability report created for repo %s, ref %s", event.repoUrl(), event.ref());
                            }
                            return null;
                        }
                    ))
                    .onFailure().invoke(failure -> Log.errorf(failure, "Pull-Request handling failed for repo %s, ref %s", event.repoUrl(), event.ref()))
                    .onFailure().recoverWithNull().onItem().ignore().andContinueWithNull();
            } else {
                Log.infof("No pull-requests open in repo %s with head %s", event.repoUrl(), event.branch());
                return Uni.createFrom().voidItem();
            }
        });
    }

    Uni<Result<VulnerabilityReporting.VulnerabilityReport>> createReport(Event<?> event, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata) {
        metadata.writeToMDC();
        return Uni.combine().all().unis(sbomResults.stream().map(sbomResult ->
                switch (sbomResult) {
                    case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                        case CdxgenClient.SBOMGenerationResult.Yield y ->
                            reporter.createVulnerabilityReport(y.sbomFile(), y.projectName());
                        case CdxgenClient.SBOMGenerationResult.None n -> {
                            Log.infof("Nothing to analyse in repo %s, ref %s", event.repoUrl(), metadata.gitRef());
                            yield Uni.createFrom().item(Result.success(VulnerabilityReporting.VulnerabilityReport.none()));
                        }
                    };

                    case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                        Log.errorf(f.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), metadata.gitRef());
                        yield Uni.createFrom().item(Result.failure(f.cause()));
                    }
                }).toList()).with(Function.identity())
            .map(results ->
                results.stream().filter(r -> r instanceof Result.Success)
                    .map(r -> ((Result.Success<VulnerabilityReporting.VulnerabilityReport>) r).result())
                    .filter(r -> r instanceof VulnerabilityReporting.VulnerabilityReport.Report)
                    .map(r -> (VulnerabilityReporting.VulnerabilityReport.Report) r)
                    .toList()
            ).map(reports -> {
                if (reports.isEmpty()) {
                    return Result.success(VulnerabilityReporting.VulnerabilityReport.none());
                } else if (reports.size() == 1) {
                    return Result.success(reports.get(0));
                } else {
                    return Result.success(VulnerabilityReporting.VulnerabilityReport.report(
                        reports.stream().map(r -> "# %s %n%n %s %n".formatted(r.projectName(), r.text())).collect(Collectors.joining()),
                        event.getRepoName()
                    ));
                }
            });
    }

    private void upsertPullRequestComment(GHPullRequest pullRequest, String reportText, String projectName) {
        var existingComment = StreamSupport.stream(pullRequest.queryComments().list().spliterator(), false)
            .filter(comment -> comment.getBody().endsWith(COMMENT_MARKER))
            .findFirst();

        var commentText = "%s\n\n%s".formatted(reportText.replace(DTRACK_PLACEHOLDER, buildDTrackProjectSearchUrl(projectName)), COMMENT_MARKER);

        existingComment.ifPresentOrElse(existing -> {
            // update existing comment
            try {
                existing.update(commentText);
                Log.infof("Updated comment %s", existing.getHtmlUrl());
            } catch (IOException e) {
                Log.errorf(e, "Failed to update comment %s", existing.getHtmlUrl());
            }
        }, () -> {
            // create new comment
            try {
                var newComment = pullRequest.comment(commentText);
                Log.infof("Created comment %s", newComment.getHtmlUrl());
            } catch (IOException e) {
                Log.errorf(e, "Failed to comment pull-request %s, repo %s", pullRequest.getNumber(), pullRequest.getRepository().getUrl());
            }
        });
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


    Uni<Tuple2<List<Result<CdxgenClient.SBOMGenerationResult>>, LocalRepository>> checkoutAndGenerateSBOMs(Event<?> event, Command.Metadata metadata, boolean fetchLicenses) {
        var checkout = Objects.requireNonNull(repoService).createCheckoutCommand(event.repository(), event.ref());
        return checkout.execute(metadata)
            .chain(checkoutResult -> generateSboms(event, checkoutResult, metadata, fetchLicenses));
    }

    Uni<Tuple2<List<Result<CdxgenClient.SBOMGenerationResult>>, LocalRepository>> generateSboms(Event<?> event, Result<LocalRepository> checkoutResult, Command.Metadata metadata, boolean fetchLicenses) {
        metadata.writeToMDC();
        return switch (checkoutResult) {
            case Result.Success<LocalRepository> s -> {
                var localRepo = s.result();
                var cmds = Objects.requireNonNull(cdxgenClient).createCommands(localRepo.dir(), event.getRepoName(), fetchLicenses, event.config());
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
        return Uni.combine().all().unis(
            commands.stream().map(cmd -> cmd.execute(metadata)).toList()
        ).with(results -> {
            metadata.writeToMDC();
            var resultClasses = results.stream().collect(Collectors.groupingBy(Object::getClass))
                .entrySet().stream().map(e -> "%s x %s".formatted(e.getValue().size(), e.getKey().getSimpleName()))
                .collect(Collectors.joining(", "));
            var sbomClasses = results.stream()
                .filter(r -> r instanceof Result.Success).map(r -> ((Result.Success<CdxgenClient.SBOMGenerationResult>) r).result())
                .collect(Collectors.groupingBy(Object::getClass))
                .entrySet().stream().map(e -> "%s x %s".formatted(e.getValue().size(), e.getKey().getSimpleName()))
                .collect(Collectors.joining(", "));
            Log.infof("Analysis of repo %s, branch %s for %s projects resulted in %s with SBOMs %s",
                metadata.repoFullName(), metadata.gitRef(), commands.size(), resultClasses, sbomClasses);
            return (List<Result<CdxgenClient.SBOMGenerationResult>>) results;
        });
    }

    String buildDTrackProjectSearchUrl(String projectName) {
        return DTRACK_PROJECT_SEARCH_TMPL.formatted(dtrackUrl, projectName);
    }

    static RepositoryDetails addQualityScore(RepositoryDetails repoDetails, Optional<SbomqsClient.QualityScore> score) {
        return score.map(qualityScore ->
            repoDetails.withAdditionalTopic(SBOM_QUALITY_TAG.formatted(qualityScore.score()))
        ).orElse(repoDetails);
    }

    static void logValidationIssues(RepositoryDetails repoDetails, List<ParseException> validationIssues) {
        if (!validationIssues.isEmpty()) {
            Log.warnf("SBOM validation issues for repo %s, ref %s: %s", repoDetails.websiteUrl(), repoDetails.version(),
                validationIssues.stream().map(Throwable::getMessage).collect(Collectors.joining("")));
        }
    }
}
