package com.mediamarktsaturn.technolinator.status;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import com.mediamarktsaturn.technolinator.sbom.ProjectMetrics;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommitStatusMetricsUpdaterTest {

    private static final String FULL_NAME = "acme/shop";
    private static final DependencyTrackProject PROJECT = new DependencyTrackProject("p1", "shop", "main", true, Optional.empty(),
        List.of("git://github.com/acme/shop.git", "https://github.com/acme/shop"), Optional.empty());
    private static final ProjectMetrics METRICS = new ProjectMetrics(1, 2, 3, 4, 0, 41.6, Instant.now());

    private final DependencyTrackClient dtrack = mock(DependencyTrackClient.class);
    private final GHRepository repository = mock(GHRepository.class);
    private final GitHubClientProvider provider = mock(GitHubClientProvider.class);

    private CommitStatusMetricsUpdater updater;

    @BeforeEach
    void setUp() throws Exception {
        var application = mock(GitHub.class);
        var app = mock(GHApp.class);
        var installation = mock(GHAppInstallation.class);
        var installationClient = mock(GitHub.class);
        when(provider.getApplicationClient()).thenReturn(application);
        when(application.getApp()).thenReturn(app);
        when(app.getInstallationByRepository("acme", "shop")).thenReturn(installation);
        when(installation.getId()).thenReturn(42L);
        when(provider.getInstallationClient(42L)).thenReturn(installationClient);
        when(installationClient.getRepository(FULL_NAME)).thenReturn(repository);
        when(repository.getName()).thenReturn("shop");
        when(repository.getGitTransportUrl()).thenReturn("git://github.com/acme/shop.git");
        when(repository.getHtmlUrl()).thenReturn(URI.create("https://github.com/acme/shop").toURL());
        when(dtrack.searchProjects("shop")).thenReturn(Uni.createFrom().item(List.of(PROJECT)));
        updater = updater(true, Duration.ofSeconds(10));
    }

    private CommitStatusMetricsUpdater updater(boolean enabled, Duration maxAge) {
        return new CommitStatusMetricsUpdater(provider, dtrack, enabled, Duration.ofMillis(20), Duration.ofMillis(40), maxAge, Clock.systemUTC());
    }

    private CommitStatusMetricsUpdater.Target target(String sha) {
        return new CommitStatusMetricsUpdater.Target(FULL_NAME, sha, "https://dtrack.example.com/projects/p1", "SBOM available");
    }

    @Test
    void addsTheMetricsOnceTheyAreCurrent() throws Exception {
        when(dtrack.getCurrentMetrics(List.of(PROJECT)))
            .thenReturn(Uni.createFrom().item(Optional.empty()))
            .thenReturn(Uni.createFrom().item(Optional.empty()))
            .thenReturn(Uni.createFrom().item(Optional.of(METRICS)));

        updater.track(target("c0ffee"));

        await().untilAsserted(() -> verify(repository).createCommitStatus("c0ffee", GHCommitState.SUCCESS,
            "https://dtrack.example.com/projects/p1",
            "SBOM available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low", CommitStatuses.CONTEXT));
        await().untilAsserted(() -> assertThat(updater.pending()).isZero());
        verify(dtrack, times(3)).getCurrentMetrics(any());
    }

    @Test
    void triesAgainAfterFailures() throws Exception {
        when(dtrack.getCurrentMetrics(any()))
            .thenReturn(Uni.createFrom().failure(new IllegalStateException("dtrack down")))
            .thenReturn(Uni.createFrom().item(Optional.of(METRICS)));

        updater.track(target("c0ffee"));

        await().untilAsserted(() -> verify(repository).createCommitStatus(eq("c0ffee"), eq(GHCommitState.SUCCESS), anyString(), anyString(), anyString()));
    }

    @Test
    void aNewerAnalysisReplacesTheOlderOne() throws Exception {
        when(dtrack.getCurrentMetrics(any())).thenReturn(Uni.createFrom().item(Optional.of(METRICS)));

        updater.track(target("old001"));
        updater.track(target("new002"));

        await().untilAsserted(() -> verify(repository).createCommitStatus(eq("new002"), any(), any(), any(), any()));
        verify(repository, never()).createCommitStatus(eq("old001"), any(), any(), any(), any());
    }

    @Test
    void givesUpAfterTheMaximumAge() throws Exception {
        when(dtrack.getCurrentMetrics(any())).thenReturn(Uni.createFrom().item(Optional.empty()));
        updater = updater(true, Duration.ofMillis(150));

        updater.track(target("c0ffee"));

        await().untilAsserted(() -> assertThat(updater.pending()).isZero());
        verify(repository, never()).createCommitStatus(any(), any(), any(), any(), any());
    }

    @Test
    void doesNothingIfDisabled() throws Exception {
        when(dtrack.getCurrentMetrics(any())).thenReturn(Uni.createFrom().item(Optional.of(METRICS)));
        var disabled = updater(false, Duration.ofSeconds(10));

        disabled.track(target("c0ffee"));

        assertThat(disabled.pending()).isZero();
        Mockito.verifyNoInteractions(dtrack);
    }
}
