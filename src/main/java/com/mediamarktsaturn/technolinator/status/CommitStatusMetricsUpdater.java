package com.mediamarktsaturn.technolinator.status;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import com.mediamarktsaturn.technolinator.sbom.ProjectMetrics;
import com.mediamarktsaturn.technolinator.sbom.RepositoryProjectMatcher;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds the risk score and the number of vulnerabilities per severity, as Dependency-Track calculates them, to the commit status of the
 * default branch, which is written right after its analysis, when Dependency-Track didn't analyse the SBOM yet.
 * <p>
 * Dependency-Track is asked repeatedly, first after {@code initial_delay}, then every time after twice as long as before, but never after more than
 * {@code max_delay}, until its metrics are current, i.e. calculated after the SBOM was processed. No attempts are made after {@code max_age} passed
 * since the analysis. A newer analysis of the same repository replaces the pending attempts.
 * <p>
 * The attempts are kept in memory only, so a restart of Technolinator ends them. The housekeeping action {@code update-commit-status} catches up on those.
 */
@ApplicationScoped
public class CommitStatusMetricsUpdater {

    /**
     * The commit status to add the metrics to
     *
     * @param fullName    owner and name of the repository
     * @param commitSha   the commit the status belongs to
     * @param targetUrl   where the status links to, as written
     * @param description what the status says, as written
     */
    public record Target(String fullName, String commitSha, String targetUrl, String description) {
    }

    private final GitHubClientProvider clientProvider;
    private final DependencyTrackClient dtrackClient;
    private final boolean enabled;
    private final Duration initialDelay, maxDelay, maxAge;
    private final Clock clock;

    // repositories (owner/name in lower case) with attempts pending
    private final Map<String, Tracking> tracked = new ConcurrentHashMap<>();

    @Inject
    public CommitStatusMetricsUpdater(
        GitHubClientProvider clientProvider,
        DependencyTrackClient dtrackClient,
        @ConfigProperty(name = "app.commit_status.dtrack_metrics.enabled") boolean enabled,
        @ConfigProperty(name = "app.commit_status.dtrack_metrics.initial_delay") Duration initialDelay,
        @ConfigProperty(name = "app.commit_status.dtrack_metrics.max_delay") Duration maxDelay,
        @ConfigProperty(name = "app.commit_status.dtrack_metrics.max_age") Duration maxAge
    ) {
        this(clientProvider, dtrackClient, enabled, initialDelay, maxDelay, maxAge, Clock.systemUTC());
    }

    CommitStatusMetricsUpdater(GitHubClientProvider clientProvider, DependencyTrackClient dtrackClient, boolean enabled,
                               Duration initialDelay, Duration maxDelay, Duration maxAge, Clock clock) {
        this.clientProvider = clientProvider;
        this.dtrackClient = dtrackClient;
        this.enabled = enabled;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /**
     * Starts asking Dependency-Track for the metrics, replacing the attempts pending for the repository.
     */
    public void track(Target target) {
        if (!enabled) {
            return;
        }
        var tracking = new Tracking(target, clock.instant());
        var previous = tracked.put(tracking.key, tracking);
        if (previous != null) {
            previous.cancel();
        }
        scheduleAttempt(tracking, 0);
    }

    /**
     * @return the number of repositories with attempts pending
     */
    int pending() {
        return tracked.size();
    }

    private void scheduleAttempt(Tracking tracking, int attempt) {
        var delay = Backoff.delay(attempt, initialDelay, maxDelay);
        if (clock.instant().plus(delay).isAfter(tracking.startedAt.plus(maxAge))) {
            Log.infof("Giving up on adding the Dependency-Track metrics to the commit status %s of repository %s after %d attempts",
                tracking.target.commitSha(), tracking.target.fullName(), attempt);
            finish(tracking);
            return;
        }
        tracking.pending = Uni.createFrom().voidItem()
            .onItem().delayIt().by(delay)
            .chain(() -> attempt(tracking))
            .onFailure().recoverWithItem(failure -> {
                Log.warnf(failure, "Could not add the Dependency-Track metrics to the commit status %s of repository %s, will try again",
                    tracking.target.commitSha(), tracking.target.fullName());
                return false;
            })
            .subscribe().with(done -> {
                if (done || tracking.cancelled) {
                    finish(tracking);
                } else {
                    scheduleAttempt(tracking, attempt + 1);
                }
            });
    }

    private void finish(Tracking tracking) {
        tracked.remove(tracking.key, tracking);
    }

    /**
     * @return true if there is nothing left to do, because the status was updated or is obsolete
     */
    private Uni<Boolean> attempt(Tracking tracking) {
        if (tracking.cancelled) {
            return Uni.createFrom().item(true);
        }
        var target = tracking.target;
        return Uni.createFrom().item(Unchecked.supplier(() -> repository(target.fullName())))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .chain(repository -> projectsOf(repository)
                .chain(dtrackClient::getCurrentMetrics)
                .chain(metrics -> {
                    if (metrics.isEmpty()) {
                        Log.debugf("Dependency-Track metrics for repository %s are not current yet", target.fullName());
                        return Uni.createFrom().item(false);
                    }
                    return Uni.createFrom().item(Unchecked.supplier(() -> {
                            if (tracking.cancelled) {
                                return true;
                            }
                            update(repository, target, metrics.get());
                            return true;
                        }))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
                }));
    }

    private GHRepository repository(String fullName) throws java.io.IOException {
        var separator = fullName.indexOf('/');
        var installation = clientProvider.getApplicationClient().getApp()
            .getInstallationByRepository(fullName.substring(0, separator), fullName.substring(separator + 1));
        return clientProvider.getInstallationClient(installation.getId()).getRepository(fullName);
    }

    private Uni<List<DependencyTrackProject>> projectsOf(GHRepository repository) {
        var name = repository.getName();
        var vcsUrl = repository.getGitTransportUrl();
        var websiteUrl = repository.getHtmlUrl().toString();
        return dtrackClient.searchProjects(name)
            .map(candidates -> RepositoryProjectMatcher.select(candidates, name, vcsUrl, websiteUrl).stream()
                .filter(DependencyTrackProject::active)
                .toList());
    }

    private void update(GHRepository repository, Target target, ProjectMetrics metrics) throws java.io.IOException {
        var description = CommitStatuses.withMetrics(target.description(), metrics);
        Log.infof("Adding the Dependency-Track metrics to the commit status %s of repository %s: %s", target.commitSha(), target.fullName(), description);
        repository.createCommitStatus(target.commitSha(), GHCommitState.SUCCESS, target.targetUrl(), description, CommitStatuses.CONTEXT);
    }

    private static final class Tracking {
        final Target target;
        final String key;
        final Instant startedAt;
        volatile boolean cancelled;
        volatile Cancellable pending;

        Tracking(Target target, Instant startedAt) {
            this.target = target;
            this.key = target.fullName().toLowerCase(Locale.ROOT);
            this.startedAt = startedAt;
        }

        void cancel() {
            cancelled = true;
            var current = pending;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
