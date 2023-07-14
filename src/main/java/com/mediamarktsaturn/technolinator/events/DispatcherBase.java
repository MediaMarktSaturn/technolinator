package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHRepository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public abstract class DispatcherBase {

    public static final String CONFIG_FILE = "technolinator.yml";

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    MetricsPublisher metricsPublisher;

    @ConfigProperty(name = "app.analysis_timeout")
    Duration analysisTimeout;

    @ConfigProperty(name = "app.enabled_repos")
    List<String> enabledRepos;

    String createTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> normalizedEnabledRepos;

    boolean isEnabledByConfig(String repoName) {
        if (normalizedEnabledRepos == null) {
            normalizedEnabledRepos = enabledRepos.stream().map(String::trim).filter(Predicate.not(String::isEmpty)).toList();
        }

        return normalizedEnabledRepos.isEmpty() || normalizedEnabledRepos.contains(repoName);
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

    enum MetricStatusRepo {
        DISABLED_BY_CONFIG,
        DISABLED_BY_REPO,
        DISABLED_PR_REPORTS,
        NON_DEFAULT_BRANCH,
        BOT_PR_IGNORED,
        ELIGIBLE_FOR_ANALYSIS,
        CONCURRENT_PR_LIMIT_EXCEEDED
    }

    enum MetricStatusAnalysis {
        NONE,
        ERROR,
        OK
    }
}
