package com.mediamarktsaturn.technolinator.events;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleSupplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHUser;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.PullRequestHandler;
import io.micrometer.core.instrument.Tag;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Actions;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OnPullRequestDispatcher extends DispatcherBase {

    private static final List<String> RELEVANT_ACTIONS = List.of(Actions.OPENED, Actions.SYNCHRONIZE, Actions.REOPENED);

    // constructor injection not possible here, because GH app extension requires a no-arg constructor
    @Inject
    PullRequestHandler pullRequestHandler;

    @ConfigProperty(name = "app.pull_requests.ignore_bots")
    boolean ignoreBotPullRequests;

    @SuppressWarnings("unused")
    void onPullRequest(@PullRequest GHEventPayload.PullRequest prPayload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> config) {
        if (!RELEVANT_ACTIONS.contains(prPayload.getAction())) {
            if (Log.isDebugEnabled()) {
                Log.debugf("Ignoring PR action %s", prPayload.getAction());
            }
            return;
        }

        var traceId = createTraceId();
        var pullRequest = prPayload.getPullRequest();
        var pushRef = pullRequest.getHead().getRef();
        var repo = prPayload.getRepository();
        var repoUrl = repo.getHtmlUrl();
        final var commitSha = pullRequest.getHead().getSha();

        final var metadata = new Command.Metadata(pushRef, repo.getFullName(), traceId, Optional.of(commitSha));
        metadata.writeToMDC();

        // metric tags
        final MetricStatusRepo status;
        final var repoName = getRepoName(repoUrl);

        if (!isEnabledByConfig(repoName)) {
            Log.infof("Repo %s excluded by global config", repoUrl);
            status = MetricStatusRepo.DISABLED_BY_CONFIG;
        } else if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.infof("Disabled for repo %s by repository config", repoUrl);
            status = MetricStatusRepo.DISABLED_BY_REPO;
        } else if (ignoreBotPullRequest(prPayload)) {
            Log.infof("Ignored bot pull-request %s of repository %s", prPayload.getNumber(), repoUrl);
            status = MetricStatusRepo.BOT_PR_IGNORED;
        } else {
            status = MetricStatusRepo.ELIGIBLE_FOR_ANALYSIS;
            Log.infof("Analyzing pull-request %s of repository %s", prPayload.getNumber(), repoUrl);

            final double analysisStart = System.currentTimeMillis();
            DoubleSupplier duration = () -> System.currentTimeMillis() - analysisStart;
            pullRequestHandler.onPullRequest(new PullRequestEvent(prPayload, config), metadata)
                .ifNoItem().after(analysisTimeout).fail()
                .map(i -> new PullRequestResult(MetricStatusAnalysis.OK))
                .onFailure().recoverWithItem(f -> new PullRequestResult(MetricStatusAnalysis.ERROR))
                .subscribe().with(
                    prResult -> {
                        metadata.writeToMDC();
                        Log.infof("Handling completed for pull-request %s of repository %s", prPayload.getNumber(), repoUrl);
                        meterRegistry.counter("vulnerability_report_duration_ms", List.of(
                            Tag.of("repo", repoName),
                            Tag.of("failure", "")
                        )).increment(duration.getAsDouble());
                        meterRegistry.counter("vulnerability_report_status", List.of(
                            Tag.of("repo", repoName),
                            Tag.of("status", prResult.status.name()))
                        ).increment();
                    },
                    failure -> {
                        metadata.writeToMDC();
                        Log.errorf(failure, "Handling failed for pull-request %s of repository %s", prPayload.getNumber(), repoUrl);
                        meterRegistry.counter("last_vulnerability_report_duration_ms", List.of(
                            Tag.of("repo", repoName),
                            Tag.of("failure", failure.getClass().getSimpleName())
                        )).increment(duration.getAsDouble());
                    }
                );
        }

        meterRegistry.counter("pull_request", List.of(
                Tag.of("status", status.name()),
                Tag.of("repo", repoName)
            )
        ).increment();
    }

    private boolean ignoreBotPullRequest(GHEventPayload.PullRequest prPayload) {
        try {
            return ignoreBotPullRequests && (isBot(prPayload.getSender()) || isBot(prPayload.getPullRequest().getUser()));
        } catch (IOException e) {
            Log.errorf(e, "Failed to determine sender/user of pr %s", prPayload.getPullRequest().getHtmlUrl());
            return false;
        }
    }

    private boolean isBot(GHUser user) throws IOException {
        return user != null &&
            ("Bot".equalsIgnoreCase(user.getType()) ||
                (user.getName() != null && user.getName().toLowerCase(Locale.ROOT).contains("[bot]")) ||
                (user.getLogin() != null && user.getLogin().toLowerCase(Locale.ROOT).contains("[bot]")));
    }

    record PullRequestResult(MetricStatusAnalysis status) {
    }
}
