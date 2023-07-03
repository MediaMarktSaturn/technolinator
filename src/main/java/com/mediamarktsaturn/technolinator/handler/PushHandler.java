package com.mediamarktsaturn.technolinator.handler;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
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
        var repoDetails = repoService.getRepositoryDetails(event);
        return checkoutAndGenerateSBOM(event, metadata)
            // wrap into deferred for ensuring onTermination is called even on pipeline setup errors
            .chain(result -> Uni.createFrom().deferred(() -> scoreAndUploadSbom(repoDetails, result.getItem1(), metadata))
                .onTermination().invoke(() -> result.getItem2().close())
            );
    }

    Uni<Result<Project>> scoreAndUploadSbom(RepositoryDetails repoDetails, Result<CdxgenClient.SBOMGenerationResult> sbomResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (sbomResult) {
            case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                // upload sbom even with validationIssues as validation is very strict and most of the issues are tolerated by dependency-track
                case CdxgenClient.SBOMGenerationResult.Proper p -> {
                    logValidationIssues(repoDetails, p.validationIssues());
                    yield doScoreAndUploadSbom(repoDetails, p.sbom(), p.sbomFile());
                }
                case CdxgenClient.SBOMGenerationResult.Fallback f -> {
                    Log.infof("Got fallback result for repo %s, ref %s", repoDetails.websiteUrl(), repoDetails.version());
                    logValidationIssues(repoDetails, f.validationIssues());
                    yield doScoreAndUploadSbom(repoDetails, f.sbom(), f.sbomFile());
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
        };
    }

    Uni<Result<Project>> doScoreAndUploadSbom(RepositoryDetails repoDetails, Bom sbom, Path sbomFile) {
        return sbomqsClient.calculateQualityScore(sbomFile)
            .map(result -> switch (result) {
                case Result.Success<SbomqsClient.QualityScore> s -> Optional.of(s.result());
                case Result.Failure<SbomqsClient.QualityScore> f -> Optional.<SbomqsClient.QualityScore>empty();
            }).onFailure().recoverWithItem(Optional::empty)
            .chain(score ->
                dtrackClient.uploadSBOM(
                    addQualityScore(repoDetails, score),
                    sbom
                ));
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