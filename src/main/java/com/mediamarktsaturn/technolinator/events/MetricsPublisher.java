package com.mediamarktsaturn.technolinator.events;

import static com.mediamarktsaturn.technolinator.Commons.NOOP_SUBSCRIBER;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MetricsPublisher {


    private final MeterRegistry meterRegistry;
    private final boolean publishRepoMetrics;

    public MetricsPublisher(
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.publish_repo_metrics")
        boolean publishRepoMetrics) {
        this.meterRegistry = meterRegistry;
        this.publishRepoMetrics = publishRepoMetrics;
    }

    /**
     * There needs to be a backing state for micrometer gauges.
     * Key is defined as "$repoName/$language", value the last language bytes number
     */
    private static final Map<String, AtomicLong> bytesByRepoLang = new ConcurrentHashMap<>();

    public void reportLanguages(GHRepository repo) {
        if (publishRepoMetrics) {
            Uni.createFrom().item(Unchecked.supplier(() -> {
                    repo.listLanguages().forEach((lang, bytes) -> {
                        var key = "%s/%s".formatted(repo.getName(), lang);
                        var langBytes = bytesByRepoLang.computeIfAbsent(key, k -> {
                            var holder = new AtomicLong(bytes);
                            meterRegistry.gauge("repo_language_bytes",
                                List.of(Tag.of("repo", repo.getName()), Tag.of("lang", lang)), holder);
                            return holder;
                        });
                        langBytes.set(bytes);
                    });
                    return null;
                })).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(failure -> Log.warnf(failure, "Could not publish repo metrics of %s", repo.getHtmlUrl()))
                .onFailure().recoverWithNull()
                .subscribe().withSubscriber(NOOP_SUBSCRIBER);
        }
    }

    /**
     * @param repo         Short name of the repository
     * @param analysisKind Type of analysis like 'push' or 'pull-request'
     */
    public void reportAnalysisStart(String repo, String analysisKind) {
        reportAnalysis(repo, analysisKind, true);
    }

    /**
     * @see #reportAnalysisStart
     */
    public void reportAnalysisCompletion(String repo, String analysisKind) {
        reportAnalysis(repo, analysisKind, false);
    }

    /**
     * There needs to be a backing state for micrometer gauges.
     * Key is defined as "$repoName/$analysisIdentifier", value the last language bytes number
     */
    private static final Map<String, AtomicInteger> analysisStatus = new ConcurrentHashMap<>();

    /**
     * Analysis [start]: true, analysis completed: false
     */
    private void reportAnalysis(String repo, String kind, boolean start) {
        var key = "%s/%s".formatted(repo, kind);
        var analysisCount = analysisStatus.computeIfAbsent(key, k -> {
            var holder = new AtomicInteger(0);
            meterRegistry.gauge("repo_analysis_current_count",
                List.of(Tag.of("repo", repo), Tag.of("kind", kind)), holder);
            return holder;
        });
        if (start) {
            analysisCount.incrementAndGet();
        } else {
            analysisCount.decrementAndGet();
        }
    }
}
