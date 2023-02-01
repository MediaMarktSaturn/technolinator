package com.mediamarktsaturn.ghbot.events;

import static com.mediamarktsaturn.ghbot.handler.PushHandler.ON_PUSH;

import java.time.Duration;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.handler.PushHandler;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;

@ApplicationScoped
public class OnPushDispatcher {

    // no-arg constructor needed for GitHub event consuming classes by the framework, thus no constructor injection here
    @Inject
    PushHandler pushHandler;

    @ConfigProperty(name = "app.analysis_timeout")
    Duration processTimeout;

    @SuppressWarnings("unused")
    void onPush(@Push GHEventPayload.Push pushPayload, @ConfigFile("technolinator.yml") Optional<TechnolinatorConfig> config, GitHub githubApi) {
        var pushRef = pushPayload.getRef();
        var repo = pushPayload.getRepository();
        var repoUrl = repo.getUrl();

        if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.infof("Disabled for repo %s", repoUrl);
        } else if (!isBranchEligibleForAnalysis(pushPayload)) {
            Log.infof("Ref %s of repository %s not eligible for analysis, ignoring.", pushRef, repoUrl);
        } else {
            Log.infof("Ref %s of repository %s eligible for analysis", pushRef, repoUrl);

            var commitSha = getEventCommit(pushPayload);

            commitSha.ifPresent(commit ->
                createGHCommitStatus(commit, repo, GHCommitState.PENDING, null, "SBOM creation running")
                    .subscribe().with(item -> {}, failure -> {})
            );

            pushHandler.onPush(new PushEvent(pushPayload, config))
                .ifNoItem().after(processTimeout).fail()
                .chain(result -> reportAnalysisResult(result, repo, commitSha))
                .onFailure().recoverWithUni(failure -> {
                    Log.errorf(failure, "Failed to handle ref %s of repository %s", pushRef, repoUrl);
                    return reportFailure(repo, commitSha, failure);
                })
                .subscribe().with(
                    message -> Log.infof("Handling completed for ref %s of repository %s", pushRef, repoUrl),
                    failure -> Log.errorf(failure, "Handling failed for ref %s of repository %s", pushRef, repoUrl)
                );
        }
    }

    Uni<GHCommitStatus> reportFailure(GHRepository repo, Optional<String> commitSha, Throwable failure) {
        var reason = failure instanceof TimeoutException ? "timed out" : "failed";
        return commitSha
            .map(commit -> createGHCommitStatus(commit, repo, GHCommitState.FAILURE, null, "SBOM analysis " + reason))
            .orElseGet(() -> Uni.createFrom().item(null));
    }

    Uni<GHCommitStatus> reportAnalysisResult(DependencyTrackClient.UploadResult uploadResult, GHRepository repo, Optional<String> commitSha) {
        return commitSha.map(commit -> {
            final GHCommitState state;
            final String url;
            final String desc;
            if (uploadResult instanceof DependencyTrackClient.UploadResult.Success) {
                desc = "SBOM available";
                state = GHCommitState.SUCCESS;
                url = ((DependencyTrackClient.UploadResult.Success) uploadResult).projectUrl();
            } else if (uploadResult instanceof DependencyTrackClient.UploadResult.Failure) {
                desc = "SBOM creation failed";
                state = GHCommitState.ERROR;
                url = ((DependencyTrackClient.UploadResult.Failure) uploadResult).baseUrl();
            } else {
                desc = "SBOM not available";
                state = GHCommitState.SUCCESS;
                url = null;
            }

            return createGHCommitStatus(commit, repo, state, url, desc);
        }).orElseGet(() -> Uni.createFrom().item(null));
    }

    Uni<GHCommitStatus> createGHCommitStatus(String commitSha, GHRepository repo, GHCommitState state, String targetUrl, String description) {
        return Uni.createFrom().item(Unchecked.supplier(() -> {
                Log.infof("Setting repo %s commit %s status to %s", repo.getUrl(), commitSha, state);
                return repo.createCommitStatus(commitSha, state, targetUrl, description, "Supply Chain Security");
            }))
            .onFailure().invoke(failure -> Log.warnf(failure, "Could not set commit %s status of %s", commitSha, repo.getName()));
    }

    static Optional<String> getEventCommit(GHEventPayload.Push pushPayload) {
        final String commitSha;
        if (pushPayload.getHead() != null) {
            commitSha = pushPayload.getHead();
        } else if (!pushPayload.getCommits().isEmpty()) {
            commitSha = pushPayload.getCommits().get(pushPayload.getCommits().size() - 1).getSha();
        } else {
            commitSha = null;
        }
        if (commitSha != null && !commitSha.isBlank()) {
            return Optional.of(commitSha);
        } else {
            return Optional.empty();
        }
    }

    static boolean isBranchEligibleForAnalysis(GHEventPayload.Push pushPayload) {
        return pushPayload.getRef().equals("refs/heads/" + pushPayload.getRepository().getDefaultBranch());
    }
}
