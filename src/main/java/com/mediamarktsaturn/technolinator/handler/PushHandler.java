package com.mediamarktsaturn.technolinator.handler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.SbomqsClient;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Orchestrator of the checkout from GitHub, SBOM-creation and upload to Dependency-Track process
 */
@ApplicationScoped
public class PushHandler extends HandlerBase {

    private static final String SBOM_QUALITY_TAG = "sbom-quality-score=%s";

    private final DependencyTrackClient dtrackClient;
    private final SbomqsClient sbomqsClient;

    public PushHandler(
        RepositoryService repoService,
        CdxgenClient cdxgenClient,
        DependencyTrackClient dtrackClient,
        SbomqsClient sbomqsClient,
        @ConfigProperty(name = "app.analysis.cdxgen.fetch_licenses")
        boolean fetchLicenses) {
        super(repoService, cdxgenClient, fetchLicenses);
        this.dtrackClient = dtrackClient;
        this.sbomqsClient = sbomqsClient;
    }

    public Uni<Result<Project>> onPush(PushEvent event, Command.Metadata metadata) {
        return checkoutAndGenerateSBOM(event, metadata)
            // wrap into deferred for ensuring onTermination is called even on pipeline setup errors
            .chain(result -> Uni.createFrom().deferred(() -> scoreAndUploadSbom(event, result.getItem1(), metadata))
                .onTermination().invoke(() -> result.getItem2().close())
            );
    }

    Uni<Result<Project>> scoreAndUploadSbom(PushEvent event, Result<CdxgenClient.SBOMGenerationResult> sbomResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (sbomResult) {
            case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                // upload sbom even with validationIssues as validation is very strict and most of the issues are tolerated by dependency-track
                case CdxgenClient.SBOMGenerationResult.Proper p -> {
                    logValidationIssues(event, p.validationIssues());
                    yield doScoreAndUploadSbom(event, p.sbom(), p.sbomFile());
                }
                case CdxgenClient.SBOMGenerationResult.Fallback f -> {
                    Log.infof("Got fallback result for repo %s, ref %s", event.repoUrl(), event.ref());
                    logValidationIssues(event, f.validationIssues());
                    yield doScoreAndUploadSbom(event, f.sbom(), f.sbomFile());
                }
                case CdxgenClient.SBOMGenerationResult.None n -> {
                    Log.infof("Nothing to analyse in repo %s, ref %s", event.repoUrl(), event.ref());
                    yield Uni.createFrom().item(Result.success(Project.none()));
                }
            };

            case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                Log.errorf(f.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), event.ref());
                yield Uni.createFrom().item(Result.failure(f.cause()));
            }
        };
    }

    Uni<Result<Project>> doScoreAndUploadSbom(PushEvent event, Bom sbom, Path sbomFile) {
        return sbomqsClient.calculateQualityScore(sbomFile)
            .map(result -> switch (result) {
                case Result.Success<SbomqsClient.QualityScore> s -> Optional.of(s.result());
                case Result.Failure<SbomqsClient.QualityScore> f -> Optional.<SbomqsClient.QualityScore>empty();
            }).onFailure().recoverWithItem(Optional::empty)
            .chain(score ->
                dtrackClient.uploadSBOM(
                    buildProjectNameFromEvent(event),
                    buildProjectVersionFromEvent(event),
                    sbom,
                    new DependencyTrackClient.ProjectDetails(
                        getProjectDescription(event),
                        getWebsiteUrl(event),
                        getVCSUrl(event),
                        getProjectTags(event, score)
                    )
                ));
    }

    static String getWebsiteUrl(PushEvent event) {
        return event.payload().getRepository().getHtmlUrl().toString();
    }

    static String getVCSUrl(PushEvent event) {
        return event.payload().getRepository().getGitTransportUrl();
    }

    static List<String> getProjectTags(PushEvent event, Optional<SbomqsClient.QualityScore> score) {
        var tags = new ArrayList<String>();
        score.ifPresent(value -> tags.add(SBOM_QUALITY_TAG.formatted(value.score())));
        try {
            tags.addAll(event.payload().getRepository().listTopics());
        } catch (IOException e) {
            Log.warnf(e, "Could not fetch topics of repo %s", event.repoUrl());
        }
        return tags;
    }

    static String getProjectDescription(PushEvent event) {
        return event.payload().getRepository().getDescription();
    }

    static void logValidationIssues(PushEvent event, List<ParseException> validationIssues) {
        if (!validationIssues.isEmpty()) {
            Log.warnf("SBOM validation issues for repo %s, ref %s: %s", event.repoUrl(), event.ref(),
                validationIssues.stream().map(Throwable::getMessage).collect(Collectors.joining("")));
        }
    }
}
