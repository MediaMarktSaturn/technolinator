package com.mediamarktsaturn.ghbot.events;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.Result;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.handler.PushHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;

@ApplicationScoped
public class OnPushDispatcher {

    // TODO: make a little bit clearer
    // no-arg constructor required for GitHub event consuming classes by the framework, thus no constructor injection here
    @Inject
    PushHandler pushHandler;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "app.analysis_timeout")
    Duration analysisTimeout;

    @ConfigProperty(name = "app.enabled_repos")
    List<String> enabledRepos;

    @SuppressWarnings("unused") // called by the quarkus-github-app extension
    void onPush(@Push GHEventPayload.Push pushPayload, @ConfigFile("technolinator.yml") Optional<TechnolinatorConfig> config) {
        var traceId = UUID.randomUUID().toString().substring(0, 8);
        var pushRef = pushPayload.getRef();
        var repo = pushPayload.getRepository();
        var repoUrl = repo.getUrl();
        final var commitSha = getEventCommit(pushPayload);

        final var metadata = new Command.Metadata(pushRef, repo.getFullName(), traceId, commitSha);
        metadata.writeToMDC();

        // metric tags
        final MetricStatusRepo status;
        final var repoName = getRepoName(repoUrl);

        if (!isEnabledByConfig(repoName)) {
            Log.infof("Repo %s excluded by global config", repoUrl);
            status = MetricStatusRepo.DISABLED_BY_CONFIG;
        } else if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.infof("Disabled for repo %s by repo config", repoUrl);
            status = MetricStatusRepo.DISABLED_BY_REPO;
        } else if (!isBranchEligibleForAnalysis(pushPayload)) {
            Log.infof("Ref %s of repository %s not eligible for analysis, ignoring.", pushRef, repoUrl);
            status = MetricStatusRepo.NON_DEFAULT_BRANCH;
        } else {
            Log.infof("Ref %s of repository %s eligible for analysis", pushRef, repoUrl);
            status = MetricStatusRepo.ELIGIBLE_FOR_ANALYSIS;

            commitSha.ifPresent(commit ->
                createGHCommitStatus(commit, repo, GHCommitState.PENDING, null, "SBOM creation running", metadata)
                    // TODO: check if NoopSubscriber makes sense
                    .subscribe().with(item -> {
                        // result handled previously by pipeline
                    }, failure -> {
                        // error logged previously by pipeline
                    })
            );

            final double analysisStart = System.currentTimeMillis();
            DoubleSupplier duration = () -> System.currentTimeMillis() - analysisStart;
            pushHandler.onPush(new PushEvent(pushPayload, config), metadata)
                .ifNoItem().after(analysisTimeout).fail()
                .chain(result -> reportAnalysisResult(result, repo, commitSha, metadata))
                .onFailure().recoverWithUni(failure -> {
                    metadata.writeToMDC();
                    Log.errorf(failure, "Failed to handle ref %s of repository %s", pushRef, repoUrl);
                    return reportFailure(repo, commitSha, failure, metadata);
                })
                .subscribe().with(
                    pushResult -> {
                        metadata.writeToMDC();
                        Log.infof("Handling completed for ref %s of repository %s", pushRef, repoUrl);
                        meterRegistry.counter("analysis_duration_ms", List.of(
                            Tag.of("repo", repoName),
                            Tag.of("failure", "")
                        )).increment(duration.getAsDouble());
                        meterRegistry.counter("analysis_status", List.of(
                            Tag.of("repo", repoName),
                            Tag.of("status", pushResult.metricStatus.name()))
                        ).increment();
                    },
                    failure -> {
                        metadata.writeToMDC();
                        Log.errorf(failure, "Handling failed for ref %s of repository %s", pushRef, repoUrl);
                        meterRegistry.counter("last_analysis_duration_ms", List.of(
                            Tag.of("repo", repoName),
                            Tag.of("failure", failure.getClass().getSimpleName())
                        )).increment(duration.getAsDouble());
                    }
                );
        }

        meterRegistry.counter("repo_push", List.of(
                Tag.of("status", status.name()),
                Tag.of("repo", repoName)
            )
        ).increment();
    }

    Uni<PushResult> reportFailure(GHRepository repo, Optional<String> commitSha, Throwable failure, Command.Metadata metadata) {
        var reason = failure instanceof TimeoutException ? "timed out" : "failed";
        return commitSha
            .map(commit -> createGHCommitStatus(commit, repo, GHCommitState.FAILURE, null, "SBOM analysis " + reason, metadata)
                .map(commitStatus -> new PushResult(commitStatus, MetricStatusAnalysis.ERROR)))
            .orElseGet(() -> Uni.createFrom().item(new PushResult(null, MetricStatusAnalysis.NONE)));
    }

    Uni<PushResult> reportAnalysisResult(Result<String> uploadResult, GHRepository repo, Optional<String> commitSha, Command.Metadata metadata) {
        return commitSha.map(commit -> {
            final GHCommitState state;
            final MetricStatusAnalysis metricStatus;
            final String url;
            final String desc;
            switch (uploadResult) {
                case Result.Success<String> s -> {
                    url = s.result();
                    if (!url.isBlank()) {
                        desc = "SBOM available";
                    } else {
                        desc = "SBOM not available";
                    }
                    state = GHCommitState.SUCCESS;
                    metricStatus = MetricStatusAnalysis.OK;
                }
                case Result.Failure<String> f -> {
                    desc = "SBOM creation failed";
                    state = GHCommitState.ERROR;
                    metricStatus = MetricStatusAnalysis.ERROR;
                    url = null;
                }
                default -> throw new IllegalStateException();
            }

            return createGHCommitStatus(commit, repo, state, url, desc, metadata)
                .map(commitStatus -> new PushResult(commitStatus, metricStatus));
        }).orElseGet(() -> Uni.createFrom().item(null));
    }

    Uni<GHCommitStatus> createGHCommitStatus(String commitSha, GHRepository repo, GHCommitState state, String targetUrl, String description, Command.Metadata metadata) {
        return Uni.createFrom().item(Unchecked.supplier(() -> {
                metadata.writeToMDC();
                Log.infof("Setting repo %s commit %s status to %s", repo.getUrl(), commitSha, state);
                return repo.createCommitStatus(commitSha, state, targetUrl, description, "Supply Chain Security");
            }))
            .onFailure().invoke(failure -> {
                metadata.writeToMDC();
                Log.warnf(failure, "Could not set commit %s status of %s", commitSha, repo.getName());
            });
    }

    private List<String> normalizedEnabledRepos;

    boolean isEnabledByConfig(String repoName) {
        if (normalizedEnabledRepos == null) {
            normalizedEnabledRepos = enabledRepos.stream().map(String::trim).filter(Predicate.not(String::isEmpty)).toList();
        }

        return normalizedEnabledRepos.isEmpty() || normalizedEnabledRepos.contains(repoName);
    }

    String getRepoName(URL repoUrl) {
        var path = repoUrl.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
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

    enum MetricStatusRepo {
        DISABLED_BY_CONFIG,
        DISABLED_BY_REPO,
        NON_DEFAULT_BRANCH,
        ELIGIBLE_FOR_ANALYSIS
    }

    enum MetricStatusAnalysis {
        NONE,
        ERROR,
        OK
    }

    record PushResult(
        GHCommitStatus commitStatus,
        MetricStatusAnalysis metricStatus
    ) {
    }
}
