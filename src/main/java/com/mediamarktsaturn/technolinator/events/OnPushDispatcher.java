package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Commons;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.report.FailureReporter;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClientHttpException;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.micrometer.core.instrument.Tag;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Handles GitHub push notifications
 */
@ApplicationScoped
public class OnPushDispatcher extends DispatcherBase {

    @ConfigProperty(name = "app.use_pending_commit_status")
    boolean usePendingCommitStatus;

    /**
     * Called by the quarkus-github-ap extension on any push event of repositories having the app installed.
     * Repo-specific configuration is shipped optionally if available at `.github/technolinator.yml`
     */
    @SuppressWarnings("unused")
    // called by the quarkus-github-app extension
    void onPush(@Push GHEventPayload.Push pushPayload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> config) {
        var traceId = createTraceId();
        var event = new PushEvent(pushPayload, config);
        final var commitSha = getEventCommit(pushPayload);

        final var metadata = new Command.Metadata(event.ref(), event.repository().getFullName(), traceId, commitSha);
        metadata.writeToMDC();

        // metric tags
        final var status = eligibility(event);
        if (status == MetricStatusRepo.ELIGIBLE_FOR_ANALYSIS) {
            analyze(event, metadata).subscribe().withSubscriber(Commons.NOOP_SUBSCRIBER);
        }

        meterRegistry.counter("repo_push", List.of(
                Tag.of("status", status.name()),
                Tag.of("repo", event.repository().getName())
            )
        ).increment();
    }

    /**
     * Tells whether the pushed ref is to be analyzed, which is only the case for the default branch of enabled repositories.
     * Requires the {@link Command.Metadata} of the push to be written to the MDC.
     */
    MetricStatusRepo eligibility(PushEvent event) {
        var repoUrl = event.repoUrl();
        if (!isEnabledByConfig(event.repository().getName())) {
            Log.infof("Repo %s excluded by global config", repoUrl);
            return MetricStatusRepo.DISABLED_BY_CONFIG;
        } else if (!event.config().map(TechnolinatorConfig::enable).orElse(true)) {
            Log.infof("Disabled for repo %s by repo config", repoUrl);
            return MetricStatusRepo.DISABLED_BY_REPO;
        } else if (!isBranchEligibleForAnalysis(event)) {
            Log.infof("Ref %s of repository %s not eligible for analysis, ignoring.", event.ref(), repoUrl);
            return MetricStatusRepo.NON_DEFAULT_BRANCH;
        } else {
            Log.infof("Ref %s of repository %s eligible for analysis", event.ref(), repoUrl);
            return MetricStatusRepo.ELIGIBLE_FOR_ANALYSIS;
        }
    }

    /**
     * Analyzes the pushed ref, which needs to be {@link #eligibility(PushEvent) eligible}, and reports the outcome via commit status,
     * status issue and metrics. Nothing happens before the returned {@link Uni} is subscribed to, it never fails.
     */
    Uni<Void> analyze(PushEvent event, Command.Metadata metadata) {
        final var repo = event.repository();
        final var repoName = repo.getName();
        final var repoUrl = event.repoUrl();
        final var pushRef = event.ref();
        final var commitSha = metadata.commitSha();

        return Uni.createFrom().deferred(() -> {
            metadata.writeToMDC();
            metricsPublisher.reportLanguages(repo);
            metricsPublisher.reportAnalysisStart(repoName, "push");

            if (usePendingCommitStatus) {
                commitSha.ifPresent(commit ->
                    createGHCommitStatus(commit, repo, GHCommitState.PENDING, null, "SBOM creation running", metadata)
                        // Uni events handled upstream, just need to run pipeline
                        .subscribe().withSubscriber(Commons.NOOP_SUBSCRIBER)
                );
            }

            final var analysisStartedAt = Instant.now();
            final double analysisStart = System.currentTimeMillis();
            DoubleSupplier duration = () -> System.currentTimeMillis() - analysisStart;
            return Uni.createFrom().deferred(() -> handler.onPush(event, metadata))
                .ifNoItem().after(analysisTimeout).fail()
                .chain(result -> reportAnalysisResult(result, repo, commitSha, metadata))
                .onFailure(TimeoutException.class).call(timeout -> reportTimeout(repo, metadata, analysisStartedAt, event.config()))
                .onFailure().recoverWithUni(failure -> {
                    metadata.writeToMDC();
                    Log.errorf(failure, "Failed to handle ref %s of repository %s", pushRef, repoUrl);
                    return reportFailure(repo, commitSha, failure, metadata);
                }).onTermination().invoke(() -> metricsPublisher.reportAnalysisCompletion(repoName, "push"))
                .onItem().invoke(pushResult -> {
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
                })
                .onFailure().invoke(failure -> {
                    metadata.writeToMDC();
                    Log.errorf(failure, "Handling failed for ref %s of repository %s", pushRef, repoUrl);
                    meterRegistry.counter("analysis_duration_ms", List.of(
                        Tag.of("repo", repoName),
                        Tag.of("failure", failure.getClass().getSimpleName())
                    )).increment(duration.getAsDouble());
                })
                .onFailure().recoverWithNull()
                .replaceWithVoid();
        });
    }

    Uni<Void> reportTimeout(GHRepository repo, Command.Metadata metadata, Instant started, Optional<TechnolinatorConfig> config) {
        if (failureReporter == null) {
            return Uni.createFrom().voidItem();
        }
        return failureReporter.onDefaultBranchTimeout(new FailureReporter.Context(repo, metadata, started, config));
    }

    Uni<PushResult> reportFailure(GHRepository repo, Optional<String> commitSha, Throwable failure, Command.Metadata metadata) {
        var reason = failure instanceof TimeoutException ? "timed out" : "failed";
        return commitSha
            .map(commit -> createGHCommitStatus(commit, repo, GHCommitState.FAILURE, null, "SBOM analysis " + reason, metadata)
                .map(commitStatus -> new PushResult(commitStatus, MetricStatusAnalysis.ERROR)))
            .orElseGet(() -> Uni.createFrom().item(new PushResult(null, MetricStatusAnalysis.NONE)));
    }

    Uni<PushResult> reportAnalysisResult(Result<Project> uploadResult, GHRepository repo, Optional<String> commitSha, Command.Metadata metadata) {
        final GHCommitState state;
        final MetricStatusAnalysis metricStatus;
        final String url;
        final String desc;
        switch (uploadResult) {
            case Result.Success<Project> s -> {
                state = GHCommitState.SUCCESS;
                switch (s.result()) {
                    case Project.Available a -> {
                        url = a.url();
                        desc = "SBOM available";
                        metricStatus = MetricStatusAnalysis.OK;
                    }
                    case Project.List l -> {
                        url = l.searchUrl();
                        desc = "SBOMs available";
                        metricStatus = MetricStatusAnalysis.OK;
                    }
                    case Project.None n -> {
                        url = null;
                        desc = "no SBOM available";
                        metricStatus = MetricStatusAnalysis.NONE;
                    }
                }
            }
            case Result.Failure<Project> f -> {
                desc = getDescriptionFromFailure(f);
                state = GHCommitState.ERROR;
                metricStatus = MetricStatusAnalysis.ERROR;
                url = null;
            }
        }

        // without a commit there's nothing to attach the status to, but the outcome is still worth a metric
        return commitSha
            .map(commit -> createGHCommitStatus(commit, repo, state, url, desc, metadata)
                .map(commitStatus -> new PushResult(commitStatus, metricStatus)))
            .orElseGet(() -> Uni.createFrom().item(new PushResult(null, metricStatus)));
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

    static boolean isBranchEligibleForAnalysis(PushEvent event) {
        return event.ref().equals(PushEvent.BRANCH_REF_PREFIX + event.defaultBranch());
    }

    static String getDescriptionFromFailure(Result.Failure<Project> failure) {
        if (failure.cause() instanceof DependencyTrackClientHttpException) {
            return "SBOM creation failed: " + failure.cause().getMessage();
        } else {
            return "SBOM creation failed";
        }
    }

    record PushResult(
        GHCommitStatus commitStatus,
        MetricStatusAnalysis metricStatus
    ) {
    }
}
