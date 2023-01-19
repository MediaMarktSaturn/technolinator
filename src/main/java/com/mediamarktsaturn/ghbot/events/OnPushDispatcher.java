package com.mediamarktsaturn.ghbot.events;

import static com.mediamarktsaturn.ghbot.handler.PushHandler.ON_PUSH;

import java.util.Optional;
import java.util.function.Consumer;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
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
            var commitSha = getEventCommit(pushPayload);
            commitSha.ifPresent(sha -> createGHCommitStatus(sha, pushPayload.getRepository(), GHCommitState.PENDING, null, githubApi));

            Consumer<AnalysisResult> resultCallback = result -> {
                commitSha.ifPresent(sha -> announceCommitStatus(sha, pushPayload.getRepository(), result, githubApi));
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

    static void announceCommitStatus(String commitSha, GHRepository repo, AnalysisResult result, GitHub githubApi) {
        var state = result.success() ? GHCommitState.SUCCESS : GHCommitState.ERROR;
        createGHCommitStatus(commitSha, repo, state, result.url(), githubApi);
    }

    static void createGHCommitStatus(String commitSha, GHRepository repo, GHCommitState state, String targetUrl, GitHub githubApi) {
        try {
            githubApi.getRepositoryById(repo.getId()).createCommitStatus(commitSha, state, targetUrl, "SBOM creation");
        } catch (Exception e) {
            Log.warnf(e, "Could not set commit %s status of %s", commitSha, repo.getName());
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
