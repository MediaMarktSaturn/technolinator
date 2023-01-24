package com.mediamarktsaturn.ghbot.handler;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;

import com.mediamarktsaturn.ghbot.events.AnalysisResult;
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
        processPushEvent(event);
    }

    void processPushEvent(PushEvent event) {
        repoService.checkoutBranch(event)
            .thenCompose(checkoutResult -> generateSbom(event, checkoutResult))
            .thenCompose(generationResult -> uploadSbom(event, generationResult))
            .whenComplete(((uploadResult, failure) -> reportAnalysisResult(event, uploadResult, failure)));
    }

    void reportAnalysisResult(PushEvent event, DependencyTrackClient.UploadResult uploadResult, Throwable failure) {
        final boolean success = failure == null &&
            uploadResult instanceof DependencyTrackClient.UploadResult.Success
            || uploadResult instanceof DependencyTrackClient.UploadResult.None;

        final String url;
        if (uploadResult instanceof DependencyTrackClient.UploadResult.Success) {
            url = ((DependencyTrackClient.UploadResult.Success) uploadResult).projectUrl();
        } else if (uploadResult instanceof DependencyTrackClient.UploadResult.Failure) {
            url = ((DependencyTrackClient.UploadResult.Failure) uploadResult).baseUrl();
        } else {
            url = null;
        }

        event.resultCallback().accept(new AnalysisResult(success, url));
    }

    CompletableFuture<CdxgenClient.SBOMGenerationResult> generateSbom(PushEvent event, RepositoryService.CheckoutResult checkoutResult) {
        if (checkoutResult instanceof RepositoryService.CheckoutResult.Success) {
            Log.infof("Starting sbom creation for repo %s, branch %s", event.repoUrl(), event.getBranch());
            final var localRepo = ((RepositoryService.CheckoutResult.Success) checkoutResult).repo();
            return cdxgenClient.generateSBOM(buildAnalysisDirectory(localRepo, event.config()), buildProjectNameFromEvent(event), event.config())
                .whenComplete((uploadResult, uploadFailure) -> localRepo.close());
        } else {
            var failure = (RepositoryService.CheckoutResult.Failure) checkoutResult;
            Log.errorf(failure.cause(), "Aborting analysis of repo %s, branch %s because of checkout failure", event.repoUrl(), event.getBranch());
            return CompletableFuture.failedFuture(failure.cause());
        }
    }

    CompletableFuture<DependencyTrackClient.UploadResult> uploadSbom(PushEvent event, CdxgenClient.SBOMGenerationResult sbomResult) {
        final CompletableFuture<DependencyTrackClient.UploadResult> uploadResult;

        // upload sbom even with validationIssues as validation is very strict and most of the issues are tolerated by dependency-track
        if (sbomResult instanceof CdxgenClient.SBOMGenerationResult.Proper) {
            var properResult = (CdxgenClient.SBOMGenerationResult.Proper) sbomResult;
            logValidationIssues(event, properResult.validationIssues());
            uploadResult = doUploadSbom(buildProjectNameFromEvent(event), buildProjectVersionFromEvent(event), properResult.sbom());
        } else if (sbomResult instanceof CdxgenClient.SBOMGenerationResult.Fallback) {
            var fallbackResult = (CdxgenClient.SBOMGenerationResult.Fallback) sbomResult;
            Log.infof("Got fallback result for repo %s, ref %s", event.repoUrl(), event.pushRef());
            logValidationIssues(event, fallbackResult.validationIssues());
            uploadResult = doUploadSbom(buildProjectNameFromEvent(event), buildProjectVersionFromEvent(event), fallbackResult.sbom());
        }

        // handle missing sbom or failure
        else if (sbomResult instanceof CdxgenClient.SBOMGenerationResult.None) {
            Log.infof("Nothing to analyse in repo %s, ref %s", event.repoUrl(), event.pushRef());
            uploadResult = CompletableFuture.completedFuture(new DependencyTrackClient.UploadResult.None());
        } else if (sbomResult instanceof CdxgenClient.SBOMGenerationResult.Failure) {
            var failure = (CdxgenClient.SBOMGenerationResult.Failure) sbomResult;
            Log.errorf(failure.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), event.pushRef());
            uploadResult = CompletableFuture.failedFuture(failure.cause());
        } else {
            throw new IllegalStateException("Unknown response type: " + sbomResult.getClass());
        }

        return uploadResult;
    }

    CompletableFuture<DependencyTrackClient.UploadResult> doUploadSbom(String projectName, String projectVersion, Bom sbom) {
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
