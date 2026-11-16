package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HousekeepingServiceTest {

    private GitHubRepositoryIndexer indexer;
    private DependencyTrackClient dtrack;
    private HousekeepingWorker worker;
    private SimpleMeterRegistry meters;
    private final List<HousekeepingUnit> processed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        indexer = mock(GitHubRepositoryIndexer.class);
        dtrack = mock(DependencyTrackClient.class);
        worker = mock(HousekeepingWorker.class);
        meters = new SimpleMeterRegistry();
        when(worker.enabledActions()).thenReturn(List.of("archive-orphaned-projects"));
        when(worker.process(any())).thenAnswer(invocation -> {
            processed.add(invocation.getArgument(0));
            return Uni.createFrom().item(true);
        });
    }

    private HousekeepingService service(List<String> enabledRepos, Duration timeout) {
        return new HousekeepingService(indexer, dtrack, worker, meters, enabledRepos, false, timeout, 3);
    }

    private HousekeepingService service() {
        return service(List.of(), Duration.ofSeconds(10));
    }

    private double lastSuccess() {
        return meters.get("housekeeping_last_success_epoch_seconds").gauge().value();
    }

    private double lastCompleted() {
        return meters.get("housekeeping_last_completed_epoch_seconds").gauge().value();
    }

    private double installationFailures() {
        return meters.get("housekeeping_installation_failures").counter().count();
    }

    private static GitHubRepositoryIndex index(GitHubRepo... repos) {
        return GitHubRepositoryIndex.builder().addInstallation("github.com", "org", true, List.of(repos)).build();
    }

    private void given(GitHubRepositoryIndex index, List<DependencyTrackProject> projects) {
        when(indexer.build(any())).thenReturn(Uni.createFrom().item(index));
        when(dtrack.listProjects(false)).thenReturn(Uni.createFrom().item(projects));
    }

    /**
     * Prepares the lookups of a selection: [searches] are pairs of search text and the projects found for it, projects have no children
     */
    private void givenTargeted(GitHubRepositoryIndex index, Object... searches) {
        when(indexer.build(any(), any())).thenReturn(Uni.createFrom().item(index));
        when(dtrack.listChildren(anyString())).thenReturn(Uni.createFrom().item(List.of()));
        for (int i = 0; i < searches.length; i += 2) {
            @SuppressWarnings("unchecked")
            var found = (List<DependencyTrackProject>) searches[i + 1];
            when(dtrack.searchProjects((String) searches[i])).thenReturn(Uni.createFrom().item(found));
        }
    }

    private static void run(HousekeepingService service) {
        service.runAll().await().atMost(Duration.ofSeconds(10));
    }

    @Test
    void handsEveryRepositoryAndProjectToTheWorkerExactlyOnce() {
        // Given
        given(
            index(repo("org", "app", false), repo("org", "archived", true), repo("org", "never-analyzed", false)),
            List.of(
                project("p1", "app", "org", "app"),
                project("p2", "archived", "org", "archived"),
                project("p3", "deleted", "org", "deleted"),
                plainProject("p4", "something-else")
            )
        );

        // When
        run(service());

        // Then
        assertThat(processed).hasSize(5);
        assertThat(processed.stream().flatMap(unit -> unit.projects().stream()).map(DependencyTrackProject::uuid))
            .containsExactlyInAnyOrder("p1", "p2", "p3", "p4");
        assertThat(processed.stream().flatMap(unit -> unit.repository().stream()).map(GitHubRepo::fullName))
            .containsExactlyInAnyOrder("org/app", "org/archived", "org/never-analyzed");
        verify(worker).begin(any());
        verify(worker).end(any());
        assertThat(lastSuccess()).isPositive();
        assertThat(lastCompleted()).isPositive();
        assertThat(installationFailures()).isZero();
        assertThat(meters.get("housekeeping_run_duration").timer().count()).isEqualTo(1);
    }

    @Test
    void honorsEnabledRepositories() {
        // Given
        given(
            index(repo("org", "app", false), repo("org", "other", false)),
            List.of(project("p1", "app", "org", "app"), project("p2", "other", "org", "other"))
        );

        // When
        run(service(List.of("App"), Duration.ofSeconds(10)));

        // Then
        assertThat(processed).hasSize(1);
        assertThat(processed.getFirst().repository()).map(GitHubRepo::fullName).contains("org/app");
    }

    @Test
    void restrictsRunToSelectedRepositoriesAndProjects() {
        // Given
        givenTargeted(
            index(repo("org", "app", false)),
            "app", List.of(project("p1", "app", "org", "app"), project("p6", "app-extra", "org", "other-app")),
            // gone, found by the project selected by name
            "deleted-too", List.of(project("p5", "deleted-too", "org", "deleted-too"))
        );

        // When
        service().run(new HousekeepingSelection(Set.of("ORG/App"), Set.of("Deleted-Too"))).await().atMost(Duration.ofSeconds(10));

        // Then
        assertThat(processed).hasSize(2);
        assertThat(processed.stream().flatMap(unit -> unit.projects().stream()).map(DependencyTrackProject::uuid)).containsExactlyInAnyOrder("p1", "p5");
        assertThat(processed.stream().map(HousekeepingUnit::repositoryState)).containsExactlyInAnyOrder(
            GitHubRepositoryIndex.State.ACTIVE, GitHubRepositoryIndex.State.GONE);
        // nothing is listed completely
        verify(indexer, never()).build(any());
        verify(dtrack, never()).listProjects(anyBoolean());
    }

    @Test
    void partialRunDoesNotAdvanceCompletionMetrics() {
        // Given
        givenTargeted(index(repo("org", "app", false)), "app", List.of(project("p1", "app", "org", "app")));

        // When
        service().run(new HousekeepingSelection(Set.of("org/app"), Set.of())).await().atMost(Duration.ofSeconds(10));

        // Then
        assertThat(processed).hasSize(1);
        assertThat(lastSuccess()).isZero();
        assertThat(lastCompleted()).isZero();
        assertThat(meters.get("housekeeping_run_duration").timer().count()).isEqualTo(1);
    }

    @Test
    void selectionMatchingNothingProcessesNothing() {
        // Given
        givenTargeted(GitHubRepositoryIndex.builder().build(), "unknown", List.of());

        // When
        service().run(new HousekeepingSelection(Set.of("org/unknown"), Set.of())).await().atMost(Duration.ofSeconds(10));

        // Then
        verify(worker, never()).process(any());
        verify(worker).end(any());
    }

    @Test
    void doesNothingIfNoActionIsEnabled() {
        // Given
        when(worker.enabledActions()).thenReturn(List.of());

        // When
        run(service());

        // Then
        verifyNoInteractions(indexer, dtrack);
        verify(worker, never()).begin(any());
        verify(worker, never()).process(any());
        assertThat(lastSuccess()).isZero();
    }

    @Test
    void abortsIfGitHubCouldNotBeListed() {
        // Given
        when(indexer.build(any())).thenReturn(Uni.createFrom().failure(new IllegalStateException("no github")));
        when(dtrack.listProjects(false)).thenReturn(Uni.createFrom().item(List.of(project("p1", "app", "org", "app"))));

        // When
        run(service());

        // Then
        verify(worker, never()).process(any());
        verify(worker).end(any());
        assertThat(lastSuccess()).isZero();
        assertThat(lastCompleted()).isZero();
    }

    @Test
    void abortsIfDependencyTrackCouldNotBeListed() {
        // Given
        when(indexer.build(any())).thenReturn(Uni.createFrom().item(index(repo("org", "app", true))));
        when(dtrack.listProjects(false)).thenReturn(Uni.createFrom().failure(new IllegalStateException("no dtrack")));

        // When
        run(service());

        // Then
        verify(worker, never()).process(any());
        verify(worker).end(any());
        assertThat(lastSuccess()).isZero();
    }

    @Test
    void doesNotReportSuccessIfAWorkerFails() {
        // Given
        given(index(repo("org", "app", true)), List.of(project("p1", "app", "org", "app")));
        when(worker.process(any())).thenReturn(Uni.createFrom().item(false));

        // When
        run(service());

        // Then
        verify(worker, times(1)).process(any());
        verify(worker).end(any());
        assertThat(lastSuccess()).isZero();
        assertThat(lastCompleted()).isPositive();
    }

    @Test
    void survivesWorkerThrowingAndKeepsGoing() {
        // Given
        given(index(repo("org", "a", false), repo("org", "b", false)), List.of());
        when(worker.process(any())).thenReturn(Uni.createFrom().failure(new IllegalStateException("boom")));

        // When
        run(service());

        // Then
        verify(worker, times(2)).process(any());
        assertThat(lastSuccess()).isZero();
        assertThat(lastCompleted()).isPositive();
    }

    @Test
    void doesNotReportSuccessIfAnInstallationFailed() {
        // Given
        given(
            GitHubRepositoryIndex.builder().addInstallation("github.com", "org", true, List.of(repo("org", "app", false))).installationFailed().build(),
            List.of(project("p1", "app", "org", "app"))
        );

        // When
        run(service());

        // Then
        verify(worker, times(1)).process(any());
        assertThat(lastSuccess()).isZero();
        assertThat(lastCompleted()).isPositive();
        assertThat(installationFailures()).isEqualTo(1);
    }

    @Test
    void onlyProcessesRepositoriesWithProjectsIfAnInstallationFailed() {
        // Given
        given(
            GitHubRepositoryIndex.builder()
                .addInstallation("github.com", "org", true, List.of(repo("org", "app", false), repo("org", "never-analyzed", false)))
                .installationFailed().build(),
            List.of(
                project("p1", "app", "org", "app"),
                // the repository might be in the installation that failed, so it can't be told whether it is gone
                project("p2", "deleted", "org", "deleted"),
                plainProject("p3", "something-else")
            )
        );

        // When
        run(service());

        // Then
        assertThat(processed).hasSize(1);
        assertThat(processed.getFirst().repository()).map(GitHubRepo::fullName).contains("org/app");
        assertThat(processed.getFirst().projects()).extracting(DependencyTrackProject::uuid).containsExactly("p1");
    }

    @Test
    void processesEverythingIfNoInstallationFailed() {
        // Given
        given(
            index(repo("org", "app", false), repo("org", "never-analyzed", false)),
            List.of(project("p1", "app", "org", "app"), project("p2", "deleted", "org", "deleted"))
        );

        // When
        run(service());

        // Then
        assertThat(processed).hasSize(3);
    }

    @Test
    void skipsRunWhileAnotherOneIsInProgress() {
        // Given
        when(indexer.build(any())).thenReturn(Uni.createFrom().item(index(repo("org", "app", false))).onItem().delayIt().by(Duration.ofMillis(500)));
        when(dtrack.listProjects(false)).thenReturn(Uni.createFrom().item(List.of()));
        var service = service();

        // When
        var first = service.runAll().subscribeAsCompletionStage();
        run(service);
        first.toCompletableFuture().join();

        // Then
        verify(indexer, times(1)).build(any());
        verify(worker, times(1)).begin(any());
        assertThat(processed).hasSize(1);

        // and the next run is possible again
        run(service);
        verify(indexer, times(2)).build(any());
    }

    @Test
    void startsInBackgroundOnlyIfNoOtherRunIsInProgress() {
        // Given
        var listing = new CompletableFuture<GitHubRepositoryIndex>();
        when(indexer.build(any())).thenReturn(Uni.createFrom().completionStage(listing));
        when(dtrack.listProjects(false)).thenReturn(Uni.createFrom().item(List.of()));
        var service = service();

        // When
        var first = service.startInBackground(HousekeepingSelection.all());
        var second = service.startInBackground(HousekeepingSelection.all());

        // Then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(service.isRunning()).isTrue();
        verify(worker, times(1)).begin(any());

        // and the next run is possible again, once the first one is done
        listing.complete(index(repo("org", "app", false)));
        assertThat(service.isRunning()).isFalse();
        assertThat(processed).hasSize(1);
        assertThat(service.startInBackground(HousekeepingSelection.all())).isTrue();
    }

    @Test
    void releasesRunIfItCouldNotBeStarted() {
        // Given
        when(worker.enabledActions()).thenThrow(new IllegalStateException("broken"));
        var service = service();

        // When
        var started = service.startInBackground(HousekeepingSelection.all());

        // Then
        assertThat(started).isTrue();
        assertThat(service.isRunning()).isFalse();
        verifyNoInteractions(indexer, dtrack);
    }

    @Test
    void abortsRunExceedingTimeout() {
        // Given
        when(indexer.build(any())).thenReturn(Uni.createFrom().nothing());
        when(dtrack.listProjects(false)).thenReturn(Uni.createFrom().item(List.of()));

        // When
        run(service(List.of(), Duration.ofMillis(200)));

        // Then
        verify(worker, never()).process(any());
        verify(worker).end(any());
        assertThat(lastSuccess()).isZero();
        assertThat(lastCompleted()).isZero();
    }

    @Test
    void releasesRunIfListingCouldNotBeStarted() {
        // Given
        when(indexer.build(any()))
            .thenThrow(new IllegalStateException("broken"))
            .thenReturn(Uni.createFrom().item(index(repo("org", "app", false))));
        when(dtrack.listProjects(false)).thenReturn(Uni.createFrom().item(List.of()));
        var service = service();

        // When
        run(service);

        // Then
        verify(worker, never()).process(any());
        verify(worker).end(any());
        assertThat(service.isRunning()).isFalse();
        assertThat(lastCompleted()).isZero();

        // and the next run is possible again
        run(service);
        assertThat(processed).hasSize(1);
    }
}
