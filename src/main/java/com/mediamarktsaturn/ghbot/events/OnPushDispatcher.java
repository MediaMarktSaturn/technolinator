package com.mediamarktsaturn.ghbot.events;

import static com.mediamarktsaturn.ghbot.handler.PushHandler.ON_PUSH;

import java.time.Duration;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
public class OnPushDispatcher {

    // no-arg constructor needed for GitHub event consuming classes by the framework, thus no constructor injection here
    @Inject
    EventBus eventBus;

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

            commitSha.ifPresent(commit -> createGHCommitStatus(commit, repo, GHCommitState.PENDING, null, "SBOM creation running"));

            eventBus.<DependencyTrackClient.UploadResult>request(
                    ON_PUSH,
                    new PushEvent(pushPayload, config),
                    new DeliveryOptions().setSendTimeout(processTimeout.toMillis())
                )
                .ifNoItem().after(processTimeout).fail()
                .subscribe().with(
                    message -> reportAnalysisResult(message.body(), repo, commitSha),
                    failure -> {
                        Log.errorf(failure, "Failed to handle ref %s of repository %s", pushRef, repoUrl);
                        var reason = failure instanceof TimeoutException ? "timed out" : "failed";
                        commitSha.ifPresent(commit -> createGHCommitStatus(commit, repo, GHCommitState.FAILURE, null, "SBOM analysis " + reason));
                    }
                );
        }
    }

    void reportAnalysisResult(DependencyTrackClient.UploadResult uploadResult, GHRepository repo, Optional<String> commitSha) {
        commitSha.ifPresent(commit -> {
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

            createGHCommitStatus(commit, repo, state, url, desc);
        });
    }

    void createGHCommitStatus(String commitSha, GHRepository repo, GHCommitState state, String targetUrl, String description) {
        Uni.createFrom().item(Unchecked.supplier(() ->
                repo.createCommitStatus(commitSha, state, targetUrl, description, "Supply Chain Security")))
            .subscribe().with(
                result -> {
                },
                failure -> Log.warnf(failure, "Could not set commit %s status of %s", commitSha, repo.getName())
            );
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
