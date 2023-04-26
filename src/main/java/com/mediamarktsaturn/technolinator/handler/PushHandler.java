package com.mediamarktsaturn.technolinator.handler;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Orchestrator of the checkout from GitHub, SBOM-creation and upload to Dependency-Track process
 */
@ApplicationScoped
public class PushHandler extends HandlerBase {

    private final DependencyTrackClient dtrackClient;

    public PushHandler(RepositoryService repoService, CdxgenClient cdxgenClient, DependencyTrackClient dtrackClient) {
        super(repoService, cdxgenClient);
        this.dtrackClient = dtrackClient;
    }

    public Uni<Result<Project>> onPush(PushEvent event, Command.Metadata metadata) {
        return checkoutAndGenerateSBOM(event, metadata)
            // wrap into deferred for ensuring onTermination is called even on pipeline setup errors
            .chain(result -> Uni.createFrom().deferred(() -> uploadSbom(event, result.getItem1(), metadata))
                .onTermination().invoke(() -> result.getItem2().close())
            );
    }

    Uni<Result<Project>> uploadSbom(PushEvent event, Result<CdxgenClient.SBOMGenerationResult> sbomResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (sbomResult) {
            case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                // upload sbom even with validationIssues as validation is very strict and most of the issues are tolerated by dependency-track
                case CdxgenClient.SBOMGenerationResult.Proper p -> {
                    logValidationIssues(event, p.validationIssues());
                    yield doUploadSbom(event, p.sbom());
                }
                case CdxgenClient.SBOMGenerationResult.Fallback f -> {
                    Log.infof("Got fallback result for repo %s, ref %s", event.repoUrl(), event.ref());
                    logValidationIssues(event, f.validationIssues());
                    yield doUploadSbom(event, f.sbom());
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

    Uni<Result<Project>> doUploadSbom(PushEvent event, Bom sbom) {
        return dtrackClient.uploadSBOM(
            buildProjectNameFromEvent(event),
            buildProjectVersionFromEvent(event),
            sbom,
            getProjectTags(event),
            getProjectDescription(event),
            getRepositoryUrl(event)
        );
    }

    static String getRepositoryUrl(PushEvent event) {
        return event.payload().getRepository().getHtmlUrl().toString();
    }

    static List<String> getProjectTags(PushEvent event) {
        try {
            return event.payload().getRepository().listTopics();
        } catch (IOException e) {
            Log.warnf(e, "Could not fetch topics of repo %s", event.repoUrl());
            return List.of();
        }
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
