package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import com.mediamarktsaturn.technolinator.report.FailureReporter;
import com.mediamarktsaturn.technolinator.status.CommitStatuses;
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

    private static final String DTRACK_PROJECT_SEARCH_TMPL = "%s/projects?searchText=%s";

    // constructor injection not possible here, because GH app extension requires a no-arg constructor
    @Inject
    AnalysisProcessHandler handler;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    MetricsPublisher metricsPublisher;

    @Inject
    FailureReporter failureReporter;

    @ConfigProperty(name = "app.analysis_timeout")
    Duration analysisTimeout;

    @ConfigProperty(name = "app.enabled_repos")
    List<String> enabledRepos;

    @ConfigProperty(name = "app.commit_status_write.enabled")
    Boolean enabledCommitStatusWrite;

    @ConfigProperty(name = "dtrack.url")
    String dtrackUrl;

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

    /**
     * @return the search for the Dependency-Track projects of the repository, null if Dependency-Track is not known
     */
    String dtrackSearchUrl(GHRepository repo) {
        if (dtrackUrl == null || dtrackUrl.isBlank() || repo.getName() == null) {
            return null;
        }
        return DTRACK_PROJECT_SEARCH_TMPL.formatted(dtrackUrl.strip(), repo.getName());
    }

    Uni<GHCommitStatus> createGHCommitStatus(String commitSha, GHRepository repo, GHCommitState state, String targetUrl, String description, Command.Metadata metadata) {
        if (!enabledCommitStatusWrite) {
            metadata.writeToMDC();
            Log.infof("Skip setting repo %s commit %s status to %s, because it's disabled via configuration.", repo.getUrl(), commitSha, state);
            return Uni.createFrom().nullItem();
        }

        final var shortDescription = CommitStatuses.limit(description);
        return Uni.createFrom().item(Unchecked.supplier(() -> {
                metadata.writeToMDC();
                Log.infof("Setting repo %s commit %s status to %s", repo.getUrl(), commitSha, state);
                return repo.createCommitStatus(commitSha, state, targetUrl, shortDescription, CommitStatuses.CONTEXT);
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
