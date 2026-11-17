package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.report.FailureReporter;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.micrometer.core.instrument.Tag;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkiverse.githubapp.event.Actions;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleSupplier;

@ApplicationScoped
public class OnPullRequestDispatcher extends DispatcherBase {

    private static final List<String> RELEVANT_ACTIONS = List.of(Actions.OPENED, Actions.SYNCHRONIZE, Actions.REOPENED);

    @ConfigProperty(name = "app.pull_requests.ignore_bots")
    boolean ignoreBotPullRequests;

    @ConfigProperty(name = "app.pull_requests.enabled")
    boolean enabled;

    @Inject
    GitHubConfigFileProvider configFileProvider;

    @SuppressWarnings("unused")
    void onPullRequest(@PullRequest GHEventPayload.PullRequest prPayload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> defaultBranchConfig) {
        if (!enabled) {
            if (Log.isDebugEnabled()) {
                Log.debug("PR analysis is disabled");
            }
            return;
        }
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

        final var config = headConfig(repo, commitSha, defaultBranchConfig);

        // metric tags
        final MetricStatusRepo status;
        final var repoName = repo.getName();

        if (!isEnabledByConfig(repoName)) {
            Log.infof("Repo %s excluded by global config", repoUrl);
            status = MetricStatusRepo.DISABLED_BY_CONFIG;
        } else if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.infof("Disabled for repo %s by repository config", repoUrl);
            status = MetricStatusRepo.DISABLED_BY_REPO;
        } else if (!config.map(TechnolinatorConfig::enablePullRequestReport).orElse(true)) {
            Log.infof("Pull-request reports disabled by repo %s", repoUrl);
            status = MetricStatusRepo.DISABLED_PR_REPORTS;
        } else if (ignoreBotPullRequest(prPayload)) {
            Log.infof("Ignored bot pull-request %s of repository %s", prPayload.getNumber(), repoUrl);
            status = MetricStatusRepo.BOT_PR_IGNORED;
        } else {
            status = MetricStatusRepo.ELIGIBLE_FOR_ANALYSIS;
            Log.infof("Analyzing pull-request %s of repository %s", prPayload.getNumber(), repoUrl);
            metricsPublisher.reportAnalysisStart(repoName, "pull-request");

            final var analysisStartedAt = Instant.now();
            final double analysisStart = System.currentTimeMillis();
            DoubleSupplier duration = () -> System.currentTimeMillis() - analysisStart;
            handler.onPullRequest(new PullRequestEvent(prPayload, config), metadata)
                .ifNoItem().after(analysisTimeout).fail()
                .onFailure(TimeoutException.class).call(timeout -> reportTimeout(repo, metadata, analysisStartedAt, config, pullRequest))
                .map(this::mapToResult)
                .onFailure().recoverWithItem(f -> new PullRequestResult(MetricStatusAnalysis.ERROR))
                .onTermination().invoke(() -> {
                    metricsPublisher.reportAnalysisCompletion(repoName, "pull-request");
                })
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
                        meterRegistry.counter("vulnerability_report_duration_ms", List.of(
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

    /**
     * The configuration of a pull-request is read from its head commit, so changes of the configuration are effective
     * (and testable) within the pull-request itself. The extension provided configuration of the default branch
     * is the fallback, if the file can't be read from the head commit.
     */
    private Optional<TechnolinatorConfig> headConfig(GHRepository repo, String commitSha, Optional<TechnolinatorConfig> defaultBranchConfig) {
        try {
            return configFileProvider.fetchConfigFile(repo, commitSha, CONFIG_FILE, ConfigFile.Source.DEFAULT, TechnolinatorConfig.class);
        } catch (RuntimeException e) {
            Log.warnf(e, "Could not read the configuration file of pull-request head %s of repository %s, falling back to the one of the default branch", commitSha, repo.getFullName());
            return defaultBranchConfig;
        }
    }

    private Uni<Void> reportTimeout(GHRepository repo, Command.Metadata metadata, Instant started, Optional<TechnolinatorConfig> config, GHPullRequest pullRequest) {
        if (failureReporter == null) {
            return Uni.createFrom().voidItem();
        }
        return failureReporter.onPullRequestTimeout(new FailureReporter.Context(repo, metadata, started, config), pullRequest);
    }

    private PullRequestResult mapToResult(Result<Project> result) {
        MetricStatusAnalysis status = switch (result) {
            case Result.Success<Project> s -> switch (s.result()) {
                case Project.Available r -> MetricStatusAnalysis.OK;
                case Project.List l -> MetricStatusAnalysis.OK;
                case Project.None n -> MetricStatusAnalysis.NONE;
            };
            case Result.Failure<?> f -> MetricStatusAnalysis.ERROR;
        };
        return new PullRequestResult(status);
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
                (user.getName() != null &&
                    (user.getName().toLowerCase(Locale.ROOT).contains("[bot]") ||
                        user.getName().toLowerCase(Locale.ROOT).endsWith("-bot"))
                ) ||
                (user.getLogin() != null &&
                    (user.getLogin().toLowerCase(Locale.ROOT).contains("[bot]") ||
                        user.getLogin().toLowerCase(Locale.ROOT).endsWith("-bot"))
                ));
    }

    record PullRequestResult(MetricStatusAnalysis status) {
    }
}
