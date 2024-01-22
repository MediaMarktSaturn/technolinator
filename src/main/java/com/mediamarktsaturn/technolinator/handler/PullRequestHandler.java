package com.mediamarktsaturn.technolinator.handler;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PullRequestEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilityReporting;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Orchestrator of the checkout from GitHub, SBOM-creation and upload to Dependency-Track process
 */
@Deprecated(forRemoval = true)
public class PullRequestHandler extends HandlerBase {

    private static final String COMMENT_MARKER = "[//]: # (Technolinator)";
    private static final String DTRACK_PLACEHOLDER = "DEPENDENCY_TRACK_URL";
    private final VulnerabilityReporting reporter;

    public PullRequestHandler(
        RepositoryService repoService,
        CdxgenClient cdxgenClient,
        VulnerabilityReporting reporter,
        @ConfigProperty(name = "app.pull_requests.cdxgen.fetch_licenses")
        boolean fetchLicenses,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl) {
        super(repoService, cdxgenClient, fetchLicenses, dtrackUrl);
        this.reporter = reporter;
    }

    public Uni<Result<VulnerabilityReporting.VulnerabilityReport>> onPullRequest(PullRequestEvent event, Command.Metadata metadata) {
        return checkoutAndGenerateSBOMs(event, metadata)
            // wrap into deferred for ensuring onTermination is called even on pipeline setup errors
            .chain(result -> Uni.createFrom().deferred(() -> createReport(event, result.getItem1(), metadata))
                .onTermination().invoke(() -> result.getItem2().close())
            ).call(report -> commentPullRequest(event, report, metadata));
    }

    @SuppressWarnings("unchecked")
    Uni<Result<VulnerabilityReporting.VulnerabilityReport>> createReport(PullRequestEvent event, List<Result<CdxgenClient.SBOMGenerationResult>> sbomResults, Command.Metadata metadata) {
        metadata.writeToMDC();
        return Uni.combine().all().unis(sbomResults.stream().map(sbomResult ->
                switch (sbomResult) {
                    case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                        case CdxgenClient.SBOMGenerationResult.Yield y ->
                            reporter.createVulnerabilityReport(y.sbomFile(), y.projectName());
                        case CdxgenClient.SBOMGenerationResult.None n -> {
                            Log.infof("Nothing to analyse in repo %s, pull-request %s", event.repoUrl(), event.payload().getNumber());
                            yield Uni.createFrom().item(Result.success(VulnerabilityReporting.VulnerabilityReport.none()));
                        }
                    };

                    case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                        Log.errorf(f.cause(), "Analysis failed for repo %s, pull-request %s", event.repoUrl(), event.payload().getNumber());
                        yield Uni.createFrom().item(Result.failure(f.cause()));
                    }
                }).toList()).combinedWith(Function.identity())
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

    Uni<Void> commentPullRequest(PullRequestEvent event, Result<VulnerabilityReporting.VulnerabilityReport> reportResult, Command.Metadata metadata) {
        return Uni.createFrom().item(() ->
            reportResult.mapSuccess(report -> {
                metadata.writeToMDC();
                if (report instanceof VulnerabilityReporting.VulnerabilityReport.Report(String reportText, String projectName)) {
                    upsertPullRequestComment(event, reportText, projectName);
                } else {
                    Log.warnf("No vulnerability report created for repo %s, pull-request %s", event.repoUrl(), event.payload().getNumber());
                }
                return null;
            })).onItem().ignore().andContinueWithNull();
    }

    private void upsertPullRequestComment(PullRequestEvent event, String reportText, String projectName) {
        var pullRequest = event.payload().getPullRequest();
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
                Log.errorf(e, "Failed to comment pull-request %s, repo %s", pullRequest.getNumber(), event.repoUrl());
            }
        });
    }
}
