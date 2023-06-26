package com.mediamarktsaturn.technolinator.handler;

import java.io.IOException;
import java.util.stream.StreamSupport;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PullRequestEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.GrypeClient;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Orchestrator of the checkout from GitHub, SBOM-creation and upload to Dependency-Track process
 */
@ApplicationScoped
public class PullRequestHandler extends HandlerBase {

    private static final String COMMENT_MARKER = "[//]: # (Technolinator)";
    private static final String DTRACK_PLACEHOLDER = "DEPENDENCY_TRACK_URL";

    private final GrypeClient grypeClient;
    private final String dtrackUrl;

    public PullRequestHandler(
        RepositoryService repoService,
        CdxgenClient cdxgenClient,
        GrypeClient grypeClient,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl,
        @ConfigProperty(name = "app.pull_requests.cdxgen.fetch_licenses")
        boolean fetchLicenses
    ) {
        super(repoService, cdxgenClient, fetchLicenses);
        this.grypeClient = grypeClient;
        this.dtrackUrl = dtrackUrl;
    }

    public Uni<Result<GrypeClient.VulnerabilityReport>> onPullRequest(PullRequestEvent event, Command.Metadata metadata) {
        return checkoutAndGenerateSBOM(event, metadata)
            // wrap into deferred for ensuring onTermination is called even on pipeline setup errors
            .chain(result -> Uni.createFrom().deferred(() -> createReport(event, result.getItem1(), metadata))
                .onTermination().invoke(() -> result.getItem2().close())
            ).invoke(report -> commentPullRequest(event, report, metadata));
    }

    Uni<Result<GrypeClient.VulnerabilityReport>> createReport(PullRequestEvent event, Result<CdxgenClient.SBOMGenerationResult> sbomResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (sbomResult) {
            case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                // upload sbom even with validationIssues as validation is very strict and most of the issues are tolerated by dependency-track
                case CdxgenClient.SBOMGenerationResult.Proper p -> grypeClient.createVulnerabilityReport(p.sbomFile());
                case CdxgenClient.SBOMGenerationResult.Fallback f -> grypeClient.createVulnerabilityReport(f.sbomFile());
                case CdxgenClient.SBOMGenerationResult.None n -> {
                    Log.infof("Nothing to analyse in repo %s, pull-request %s", event.repoUrl(), event.payload().getNumber());
                    yield Uni.createFrom().item(Result.success(GrypeClient.VulnerabilityReport.none()));
                }
            };

            case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                Log.errorf(f.cause(), "Analysis failed for repo %s, pull-request %s", event.repoUrl(), event.payload().getNumber());
                yield Uni.createFrom().item(Result.failure(f.cause()));
            }
        };
    }

    Uni<Void> commentPullRequest(PullRequestEvent event, Result<GrypeClient.VulnerabilityReport> reportResult, Command.Metadata metadata) {
        return Uni.createFrom().item(() ->
            reportResult.mapSuccess(report -> {
                metadata.writeToMDC();
                if (report instanceof GrypeClient.VulnerabilityReport.Report(String reportText)) {
                    upsertPullRequestComment(event, reportText);
                } else {
                    Log.warnf("No vulnerability report created for repo %s, pull-request %s", event.repoUrl(), event.payload().getNumber());
                }
                return null;
            })).onItem().ignore().andContinueWithNull();
    }

    private void upsertPullRequestComment(PullRequestEvent event, String reportText) {
        var pullRequest = event.payload().getPullRequest();
        var existingComment = StreamSupport.stream(pullRequest.queryComments().list().spliterator(), false)
            .filter(comment -> comment.getBody().endsWith(COMMENT_MARKER))
            .findFirst();

        var commentText = "%s\n\n%s".formatted(reportText.replace(DTRACK_PLACEHOLDER, dtrackUrl), COMMENT_MARKER);

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
