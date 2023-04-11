package com.mediamarktsaturn.technolinator.handler;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
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
public class PushHandler {

    private final RepositoryService repoService;
    private final CdxgenClient cdxgenClient;
    private final DependencyTrackClient dtrackClient;

    public PushHandler(RepositoryService repoService, CdxgenClient cdxgenClient, DependencyTrackClient dtrackClient) {
        this.repoService = repoService;
        this.cdxgenClient = cdxgenClient;
        this.dtrackClient = dtrackClient;
    }

    public Uni<Result<Project>> onPush(PushEvent event, Command.Metadata metadata) {
        var checkout = repoService.createCheckoutCommand(event);
        return checkout.execute(metadata)
            .chain(checkoutResult -> generateSbom(event, checkoutResult, metadata))
            .chain(generationResult -> uploadSbom(event, generationResult, metadata));
    }

    Uni<Result<CdxgenClient.SBOMGenerationResult>> generateSbom(PushEvent event, Result<LocalRepository> checkoutResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (checkoutResult) {
            case Result.Success<LocalRepository> s -> {
                var localRepo = s.result();
                var cmd = cdxgenClient.createCommand(localRepo.dir(), buildProjectNameFromEvent(event), event.config());
                yield cmd.execute(metadata)
                    .onTermination().invoke((uploadResult, uploadFailure, wasCancelled) -> localRepo.close());
            }
            case Result.Failure<LocalRepository> f -> {
                Log.errorf(f.cause(), "Aborting analysis of repo %s, branch %s because of checkout failure", event.repoUrl(), event.getBranch());
                yield Uni.createFrom().item(Result.failure(f.cause()));
            }
        };
    }

    Uni<Result<Project>> uploadSbom(PushEvent event, Result<CdxgenClient.SBOMGenerationResult> sbomResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (sbomResult) {
            case Result.Success<CdxgenClient.SBOMGenerationResult> s -> switch (s.result()) {
                // upload sbom even with validationIssues as validation is very strict and most of the issues are tolerated by dependency-track
                case CdxgenClient.SBOMGenerationResult.Proper p -> {
                    logValidationIssues(event, p.validationIssues());
                    yield doUploadSbom(buildProjectNameFromEvent(event), buildProjectVersionFromEvent(event), p.sbom());
                }
                case CdxgenClient.SBOMGenerationResult.Fallback f -> {
                    Log.infof("Got fallback result for repo %s, ref %s", event.repoUrl(), event.pushRef());
                    logValidationIssues(event, f.validationIssues());
                    yield doUploadSbom(buildProjectNameFromEvent(event), buildProjectVersionFromEvent(event), f.sbom());
                }
                case CdxgenClient.SBOMGenerationResult.None n -> {
                    Log.infof("Nothing to analyse in repo %s, ref %s", event.repoUrl(), event.pushRef());
                    yield Uni.createFrom().item(Result.success(Project.none()));
                }
            };

            case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                Log.errorf(f.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), event.pushRef());
                yield Uni.createFrom().failure(f.cause());
            }
        };
    }

    Uni<Result<Project>> doUploadSbom(String projectName, String projectVersion, Bom sbom) {
        return dtrackClient.uploadSBOM(projectName, projectVersion, sbom);
    }

    static void logValidationIssues(PushEvent event, List<ParseException> validationIssues) {
        if (!validationIssues.isEmpty()) {
            Log.warnf("SBOM validation issues for repo %s, ref %s: %s", event.repoUrl(), event.pushRef(),
                validationIssues.stream().map(Throwable::getMessage).collect(Collectors.joining("")));
        }
    }

    static Path buildAnalysisDirectory(LocalRepository repo, Optional<TechnolinatorConfig> config) {
        return config
            .map(TechnolinatorConfig::analysis)
            .map(TechnolinatorConfig.AnalysisConfig::location)
            .map(String::trim)
            .map(repo.dir()::resolve)
            .orElse(repo.dir());
    }

    static String buildProjectNameFromSBOM(CdxgenClient.SBOMGenerationResult.Proper result, Optional<TechnolinatorConfig> config) {
        return config
            .map(TechnolinatorConfig::project)
            .map(TechnolinatorConfig.ProjectConfig::name)
            .orElseGet(() -> "%s.%s".formatted(result.group(), result.name()));
    }

    static String buildProjectNameFromEvent(PushEvent event) {
        return event.config()
            .map(TechnolinatorConfig::project)
            .map(TechnolinatorConfig.ProjectConfig::name)
            .orElseGet(() -> {
                var path = event.repoUrl().getPath();
                return path.substring(path.lastIndexOf('/') + 1);
            });
    }

    static String buildProjectVersionFromEvent(PushEvent event) {
        return event.getBranch();
    }

}
