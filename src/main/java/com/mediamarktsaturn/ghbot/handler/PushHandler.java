package com.mediamarktsaturn.ghbot.handler;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import org.cyclonedx.model.Bom;

import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.git.LocalRepository;
import com.mediamarktsaturn.ghbot.git.RepositoryService;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.sbom.CdxgenClient;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;
import io.quarkus.logging.Log;
import io.quarkus.vertx.ConsumeEvent;

@ApplicationScoped
public class PushHandler {

    public static final String ON_PUSH = "onPush";

    private final RepositoryService repoService;
    private final CdxgenClient cdxgenClient;
    private final DependencyTrackClient dtrackClient;

    public PushHandler(RepositoryService repoService, CdxgenClient cdxgenClient, DependencyTrackClient dtrackClient) {
        this.repoService = repoService;
        this.cdxgenClient = cdxgenClient;
        this.dtrackClient = dtrackClient;
    }

    @ConsumeEvent(ON_PUSH)
    public void onPush(PushEvent event) {
        if (isBranchEligibleForAnalysis(event)) {
            Log.infof("Ref %s of repository %s eligible for analysis", event.pushRef(), event.repoUrl());
            processPushEvent(event);
        } else {
            Log.infof("Ref %s of repository %s not eligible for analysis, ignoring.", event.pushRef(), event.repoUrl());
        }
    }

    void processPushEvent(PushEvent event) {
        var branch = getBranchNameFromRef(event.pushRef());
        repoService.checkoutBranch(event.repoUrl(), branch)
            .thenCompose(result -> {
                if (result instanceof RepositoryService.CheckoutResult.Success) {
                    final var localRepo = ((RepositoryService.CheckoutResult.Success) result).repo();
                    return analyseAndUploadTypedRepo(event, localRepo)
                        .whenComplete((uploadResult, uploadFailure) -> localRepo.close());
                } else {
                    var failure = (RepositoryService.CheckoutResult.Failure) result;
                    Log.errorf(failure.cause(), "Aborting analysis of repo %, branch %s because of checkout failure", event.repoUrl(), branch);
                    return CompletableFuture.failedFuture(failure.cause());
                }
            });
    }

    CompletableFuture<DependencyTrackClient.UploadResult> analyseAndUploadTypedRepo(PushEvent event, LocalRepository repo) {
        return cdxgenClient.generateSBOM(buildAnalysisDirectory(repo, event.config()))
            .thenCompose(result -> {
                final CompletableFuture<DependencyTrackClient.UploadResult> uploadResult;

                // validation issues
                if (result instanceof CdxgenClient.SBOMGenerationResult.Invalid) {
                    var invalidResult = (CdxgenClient.SBOMGenerationResult.Invalid) result;
                    Log.infof("SBOM validation issues for repo %s, ref %s: %s", event.repoUrl(), event.pushRef(),
                        invalidResult.validationIssues().stream().map(Throwable::getMessage).collect(Collectors.joining("")));
                    uploadResult = CompletableFuture.completedFuture(new DependencyTrackClient.UploadResult.None());
                }

                // upload sbom
                else if (result instanceof CdxgenClient.SBOMGenerationResult.Proper) {
                    var properResult = (CdxgenClient.SBOMGenerationResult.Proper) result;
                    uploadResult = uploadSBOM(buildProperProjectName(properResult, event.config()), properResult.version(), properResult.sbom());
                } else if (result instanceof CdxgenClient.SBOMGenerationResult.Fallback) {
                    var fallbackResult = (CdxgenClient.SBOMGenerationResult.Fallback) result;
                    Log.infof("Got fallback result for repo %s, ref %s", event.repoUrl(), event.pushRef());
                    uploadResult = uploadSBOM(buildFallbackProjectName(event), buildFallbackProjectVersion(event), fallbackResult.sbom());
                }

                // handle missing sbom or failure
                else if (result instanceof CdxgenClient.SBOMGenerationResult.None) {
                    Log.infof("Nothing to analyse in repo %s, ref %s", event.repoUrl(), event.pushRef());
                    uploadResult = CompletableFuture.completedFuture(new DependencyTrackClient.UploadResult.None());
                } else if (result instanceof CdxgenClient.SBOMGenerationResult.Failure) {
                    var failure = (CdxgenClient.SBOMGenerationResult.Failure) result;
                    Log.errorf(failure.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), event.pushRef());
                    uploadResult = CompletableFuture.failedFuture(failure.cause());
                } else {
                    throw new IllegalStateException("Unknown response type: " + result.getClass());
                }

                return uploadResult;
            });
    }

    CompletableFuture<DependencyTrackClient.UploadResult> uploadSBOM(String projectName, String projectVersion, Bom sbom) {
        return dtrackClient.uploadSBOM(projectName, projectVersion, sbom)
            .whenComplete((result, failure) -> {
                if (failure != null || result instanceof DependencyTrackClient.UploadResult.Failure) {
                    Log.errorf(failure != null ? failure : ((DependencyTrackClient.UploadResult.Failure) result).cause(), "SBOM project %s, version %s failed", projectName, projectVersion);
                } else {
                    var success = (DependencyTrackClient.UploadResult.Success) result;
                    Log.infof("Processing completed for project %s, version %s", projectName, projectVersion);
                }
            });
    }

    static File buildAnalysisDirectory(LocalRepository repo, Optional<TechnolinatorConfig> config) {
        return config
            .map(TechnolinatorConfig::analysis)
            .map(TechnolinatorConfig.AnalysisConfig::location)
            .map(String::trim)
            .map(location -> new File(repo.dir(), location))
            .orElse(repo.dir());
    }

    static String buildProperProjectName(CdxgenClient.SBOMGenerationResult.Proper result, Optional<TechnolinatorConfig> config) {
        return config
            .map(TechnolinatorConfig::project)
            .map(TechnolinatorConfig.ProjectConfig::name)
            .orElseGet(() -> "%s.%s".formatted(result.group(), result.name()));
    }

    static String buildFallbackProjectName(PushEvent event) {
        return event.config()
            .map(TechnolinatorConfig::project)
            .map(TechnolinatorConfig.ProjectConfig::name)
            .orElseGet(() -> {
                var path = event.repoUrl().getPath();
                return path.substring(path.lastIndexOf('/') + 1);
            });
    }

    static String buildFallbackProjectVersion(PushEvent event) {
        return event.pushRef().replaceFirst("refs/heads/", "");
    }

    static String getBranchNameFromRef(String ref) {
        return ref.replaceFirst("refs/heads/", "");
    }

    static boolean isBranchEligibleForAnalysis(PushEvent event) {
        return event.pushRef().equals("refs/heads/" + event.defaultBranch());
    }
}
