package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.housekeeping.HousekeepingWorker.Feature;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.ProjectMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HousekeepingUpdateCommitStatusTest {

    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", Instant.parse("2026-01-01T02:00:00Z"), false);
    private static final HousekeepingRun DRY_RUN = new HousekeepingRun("run-2", Instant.parse("2026-01-01T02:00:00Z"), true);
    private static final ProjectMetrics METRICS = new ProjectMetrics(1, 2, 3, 4, 0, 41.6, Instant.now());
    private static final String PROJECT_URL = "https://dtrack.example.com/projects/p1";
    private static final String HEAD = "c0ffee";
    private static final String CONTEXT = "Supply Chain Security";

    private DependencyTrackClient dtrack;
    private GitHubInstallationClient installation;
    private GHRepository github;
    private HousekeepingWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        dtrack = mock(DependencyTrackClient.class);
        installation = mock(GitHubInstallationClient.class);
        github = mock(GHRepository.class);
        var client = mock(GitHub.class);
        var branch = mock(GHBranch.class);
        when(installation.current()).thenReturn(client);
        when(client.getRepository("org/app")).thenReturn(github);
        when(github.getDefaultBranch()).thenReturn("main");
        when(github.getBranch("main")).thenReturn(branch);
        when(branch.getSHA1()).thenReturn(HEAD);
        when(dtrack.getCurrentMetrics(anyList())).thenReturn(Uni.createFrom().item(Optional.of(METRICS)));

        worker = new HousekeepingWorker(dtrack, mock(GitHubRepositoryTopics.class), mock(GitHubRepositoryConfig.class), new SimpleMeterRegistry(),
            new HousekeepingWorker.Settings(Duration.ofSeconds(10), Feature.off(), Feature.off(), Feature.off(), Feature.off(), Feature.off(), Feature.on(10)));
        worker.begin(RUN);
        worker.begin(DRY_RUN);
    }

    private HousekeepingUnit unit(HousekeepingRun run, boolean archived) {
        var repository = repo("org", "app", archived);
        var withInstallation = new GitHubRepo(repository.reference(), repository.name(), repository.fullName(), repository.htmlUrl(), repository.gitUrl(),
            archived, null, installation);
        return HousekeepingUnit.of(run, withInstallation, List.of(project("p1", "app", "org", "app")));
    }

    private static GHCommitStatus status(GHCommitState state, String description, String context) {
        var status = mock(GHCommitStatus.class);
        when(status.getState()).thenReturn(state);
        when(status.getDescription()).thenReturn(description);
        when(status.getContext()).thenReturn(context);
        when(status.getTargetUrl()).thenReturn(PROJECT_URL);
        return status;
    }

    /**
     * The statuses of the head commit, newest first
     */
    @SuppressWarnings("unchecked")
    private void statuses(GHCommitStatus... statuses) {
        var iterable = mock(PagedIterable.class);
        var iterator = mock(PagedIterator.class);
        when(github.listCommitStatuses(HEAD)).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(iterator);
        var remaining = new java.util.ArrayDeque<>(Arrays.asList(statuses));
        when(iterator.hasNext()).thenAnswer(invocation -> !remaining.isEmpty());
        when(iterator.next()).thenAnswer(invocation -> remaining.poll());
    }

    @Test
    void addsTheMetricsToTheStatusOfTheHeadCommit() throws IOException {
        statuses(status(GHCommitState.SUCCESS, "SBOM available", CONTEXT));

        var success = await(worker.process(unit(RUN, false)));

        assertThat(success).isTrue();
        verify(github).createCommitStatus(HEAD, GHCommitState.SUCCESS, PROJECT_URL,
            "SBOM available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low", CONTEXT);
    }

    @Test
    void usesTheLatestStatusOfTechnolinatorOnly() throws IOException {
        statuses(
            status(GHCommitState.SUCCESS, "build passed", "ci/build"),
            status(GHCommitState.SUCCESS, "SBOMs available", CONTEXT),
            status(GHCommitState.PENDING, "SBOM creation running", CONTEXT));

        await(worker.process(unit(RUN, false)));

        verify(github).createCommitStatus(HEAD, GHCommitState.SUCCESS, PROJECT_URL,
            "SBOMs available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low", CONTEXT);
    }

    @Test
    void replacesOutdatedMetrics() throws IOException {
        statuses(status(GHCommitState.SUCCESS, "SBOM available, risk score 99, vulnerabilities: 9 critical, 0 high, 0 medium, 0 low", CONTEXT));

        await(worker.process(unit(RUN, false)));

        verify(github).createCommitStatus(HEAD, GHCommitState.SUCCESS, PROJECT_URL,
            "SBOM available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low", CONTEXT);
    }

    @Test
    void leavesAStatusTellingTheCurrentMetricsAlone() throws IOException {
        statuses(status(GHCommitState.SUCCESS, "SBOM available, risk score 42, vulnerabilities: 1 critical, 2 high, 3 medium, 4 low", CONTEXT));

        await(worker.process(unit(RUN, false)));

        verify(github, never()).createCommitStatus(any(), any(), any(), any(), any());
    }

    @Test
    void leavesStatusesOfOtherOutcomesAlone() throws IOException {
        for (var description : List.of("SBOM creation failed", "no SBOM available", "SBOM creation running")) {
            for (var state : List.of(GHCommitState.FAILURE, GHCommitState.ERROR, GHCommitState.PENDING)) {
                statuses(status(state, description, CONTEXT));
                await(worker.process(unit(RUN, false)));
            }
        }
        statuses(status(GHCommitState.SUCCESS, "no SBOM available", CONTEXT));
        await(worker.process(unit(RUN, false)));

        verify(github, never()).createCommitStatus(any(), any(), any(), any(), any());
    }

    @Test
    void leavesCommitsWithoutStatusAlone() throws IOException {
        statuses(status(GHCommitState.SUCCESS, "build passed", "ci/build"));
        await(worker.process(unit(RUN, false)));
        statuses();
        await(worker.process(unit(RUN, false)));

        verify(github, never()).createCommitStatus(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotTouchGitHubWithoutCurrentMetrics() {
        when(dtrack.getCurrentMetrics(anyList())).thenReturn(Uni.createFrom().item(Optional.empty()));

        var success = await(worker.process(unit(RUN, false)));

        assertThat(success).isTrue();
        verifyNoInteractions(installation);
    }

    @Test
    void failsIfGitHubFails() throws IOException {
        when(github.listCommitStatuses(HEAD)).thenThrow(new IllegalStateException("GitHub down"));

        assertThat(await(worker.process(unit(RUN, false)))).isFalse();
    }

    @Test
    void failsIfDependencyTrackFails() {
        when(dtrack.getCurrentMetrics(anyList())).thenReturn(Uni.createFrom().failure(new IllegalStateException("dtrack down")));

        assertThat(await(worker.process(unit(RUN, false)))).isFalse();
        verifyNoInteractions(installation);
    }

    @Test
    void dryRunOnlyLogs() throws IOException {
        statuses(status(GHCommitState.SUCCESS, "SBOM available", CONTEXT));

        assertThat(await(worker.process(unit(DRY_RUN, false)))).isTrue();

        verify(github, never()).createCommitStatus(any(), any(), any(), any(), any());
    }

    @Test
    void leavesArchivedRepositoriesAlone() {
        await(worker.process(unit(RUN, true)));

        verifyNoInteractions(dtrack);
        verifyNoInteractions(installation);
    }

    @Test
    void isOffIfSwitchedOff() {
        var off = new HousekeepingWorker(dtrack, mock(GitHubRepositoryTopics.class), mock(GitHubRepositoryConfig.class), new SimpleMeterRegistry(),
            new HousekeepingWorker.Settings(Duration.ofSeconds(10), Feature.off(), Feature.off(), Feature.off(), Feature.off(), Feature.off(), Feature.off()));

        assertThat(off.enabled()).isFalse();
        assertThat(worker.enabledActions()).containsExactly(HousekeepingWorker.UPDATE_COMMIT_STATUS);
    }
}
