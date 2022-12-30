package com.mediamarktsaturn.ghbot.handler;

import java.util.concurrent.CompletableFuture;

import javax.enterprise.context.ApplicationScoped;

import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.git.LocalRepository;
import com.mediamarktsaturn.ghbot.git.RepositoryService;
import com.mediamarktsaturn.ghbot.sbom.CdxgenClient;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;

import io.quarkus.logging.Log;
import io.quarkus.vertx.ConsumeEvent;
import org.cyclonedx.model.Bom;

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
                    return performSBOMAnalysis(event, localRepo)
                        .whenComplete((uploadResult, uploadFailure) -> localRepo.close());
                } else {
                    var failure = (RepositoryService.CheckoutResult.Failure) result;
                    Log.errorf(failure.cause(), "Aborting analysis of repo %, branch %s because of checkout failure", event.repoUrl(), branch);
                    return CompletableFuture.failedFuture(failure.cause());
                }
            });
    }

    CompletableFuture<DependencyTrackClient.UploadResult> performSBOMAnalysis(PushEvent event, LocalRepository repo) {
        var type = repo.determineType();
        switch (type) {
            case UNKNOWN:
                Log.warnf("Unknown project type in repo %s, ref %s", event.repoUrl(), event.pushRef());
                return CompletableFuture.completedFuture(null);
            default:
                return analyseAndUploadTypedRepo(event, repo, type);
        }
    }

    CompletableFuture<DependencyTrackClient.UploadResult> analyseAndUploadTypedRepo(PushEvent event, LocalRepository repo, LocalRepository.Type type) {
        return cdxgenClient.generateSBOM(repo.dir())
            .thenCompose(result -> {
                final CompletableFuture<DependencyTrackClient.UploadResult> uploadResult;
                if (result instanceof CdxgenClient.SBOMGenerationResult.Proper) {
                    var properResult = (CdxgenClient.SBOMGenerationResult.Proper) result;
                    uploadResult = uploadSBOM(buildProperProjectName(properResult), properResult.version(), properResult.sbom());
                } else {
                    var failure = (CdxgenClient.SBOMGenerationResult.Failure) result;
                    Log.errorf(failure.cause(), "Analysis failed for repo %s, ref %s", event.repoUrl(), event.pushRef());
                    uploadResult = CompletableFuture.failedFuture(failure.cause());
                }
                return uploadResult;
            });
    }

    String buildProperProjectName(CdxgenClient.SBOMGenerationResult.Proper result) {
        return "%s.%s".formatted(result.group(), result.name());
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

    String getBranchNameFromRef(String ref) {
        return ref.replaceFirst("refs/heads/", "");
    }

    boolean isBranchEligibleForAnalysis(PushEvent event) {
        return event.pushRef().equals("refs/heads/" + event.defaultBranch());
    }
}
