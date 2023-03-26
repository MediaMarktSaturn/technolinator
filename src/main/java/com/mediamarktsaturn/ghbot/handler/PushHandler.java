package com.mediamarktsaturn.ghbot.handler;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;

import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.Result;
import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.git.LocalRepository;
import com.mediamarktsaturn.ghbot.git.RepositoryService;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.sbom.CdxgenClient;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;

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

    public Uni<Result<String>> onPush(PushEvent event, Command.Metadata metadata) {
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
                yield Uni.createFrom().item(new Result.Failure<>(f.cause()));
            }
        };
    }

    Uni<Result<String>> uploadSbom(PushEvent event, Result<CdxgenClient.SBOMGenerationResult> sbomResult, Command.Metadata metadata) {
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
                    yield Uni.createFrom().item(new Result.Success<>(""));
                }
            };

            case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> {
                Log.errorf(f.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), event.pushRef());
                yield Uni.createFrom().failure(f.cause());
            }
        };
    }

    Uni<Result<String>> doUploadSbom(String projectName, String projectVersion, Bom sbom) {
        return dtrackClient.uploadSBOM(projectName, projectVersion, sbom);
    }

    static void logValidationIssues(PushEvent event, List<ParseException> validationIssues) {
        if (!validationIssues.isEmpty()) {
            Log.warnf("SBOM validation issues for repo %s, ref %s: %s", event.repoUrl(), event.pushRef(),
                validationIssues.stream().map(Throwable::getMessage).collect(Collectors.joining("")));
        }
    }

    static File buildAnalysisDirectory(LocalRepository repo, Optional<TechnolinatorConfig> config) {
        return config
            .map(TechnolinatorConfig::analysis)
            .map(TechnolinatorConfig.AnalysisConfig::location)
            .map(String::trim)
            .map(location -> new File(repo.dir(), location))
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
