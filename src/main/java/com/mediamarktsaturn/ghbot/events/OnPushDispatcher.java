package com.mediamarktsaturn.ghbot.events;

import static com.mediamarktsaturn.ghbot.handler.PushHandler.ON_PUSH;

import java.util.Optional;
import java.util.function.Consumer;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
public class OnPushDispatcher {

    // no-arg constructor needed for GitHub event consuming classes by the framework, thus no constructor injection here
    @Inject
    EventBus eventBus;

    void onPush(@Push GHEventPayload.Push pushPayload, @ConfigFile("technolinator.yml") Optional<TechnolinatorConfig> config, GitHub githubApi) {
        if (config.map(TechnolinatorConfig::enable).orElse(true)) {
            var repoId = pushPayload.getRepository().getId();
            var commitSha = getEventCommit(pushPayload);
            commitSha.ifPresent(sha -> createGHCommitStatus(sha, repoId, GHCommitState.PENDING, githubApi));

            Consumer<AnalysisResult> resultCallback = result -> {
                commitSha.ifPresent(sha -> announceCommitStatus(sha,repoId, result, githubApi));
            };

            eventBus.send(ON_PUSH, new PushEvent(
                pushPayload.getRepository().getUrl(),
                pushPayload.getRef(),
                pushPayload.getRepository().getDefaultBranch(),
                resultCallback,
                config
            ));
        } else {
            Log.infof("Disabled for repo %s", pushPayload.getRepository().getUrl());
        }
    }

    static void announceCommitStatus(String commitSha, Long repoId, AnalysisResult result, GitHub githubApi) {
        var state = result.success() ? GHCommitState.SUCCESS : GHCommitState.ERROR;
        createGHCommitStatus(commitSha, repoId, state, githubApi);
    }

    static void createGHCommitStatus(String commitSha, Long repoId, GHCommitState state, GitHub githubApi) {
        try {
            githubApi.getRepositoryById(repoId).createCommitStatus(commitSha, state, null, null);
        } catch (Exception e) {
            Log.warnf("Could not set commit %s status of %s");
        }
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

}
