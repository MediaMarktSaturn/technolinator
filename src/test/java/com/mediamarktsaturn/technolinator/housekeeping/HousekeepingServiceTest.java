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
import java.util.concurrent.CopyOnWriteArrayList;

import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        given(
            index(repo("org", "app", false), repo("org", "other", false), repo("org", "third", false), repo("org", "fourth", false)),
            List.of(
                project("p1", "app", "org", "app"),
                project("p2", "other", "org", "other"),
                project("p3", "third", "org", "third"),
                project("p4", "deleted", "org", "deleted"),
                project("p5", "deleted-too", "org", "deleted-too")
            )
        );

        // When
        service().run(new HousekeepingSelection(Set.of("ORG/App", "deleted-too"), Set.of("p3", "Other"))).await().atMost(Duration.ofSeconds(10));

        // Then
        assertThat(processed.stream().flatMap(unit -> unit.projects().stream()).map(DependencyTrackProject::uuid))
            .containsExactlyInAnyOrder("p1", "p2", "p3", "p5");
        assertThat(processed).hasSize(4);
    }

    @Test
    void partialRunDoesNotAdvanceCompletionMetrics() {
        // Given
        given(index(repo("org", "app", false)), List.of(project("p1", "app", "org", "app")));

        // When
        service().run(new HousekeepingSelection(Set.of("app"), Set.of())).await().atMost(Duration.ofSeconds(10));

        // Then
        assertThat(processed).hasSize(1);
        assertThat(lastSuccess()).isZero();
        assertThat(lastCompleted()).isZero();
        assertThat(meters.get("housekeeping_run_duration").timer().count()).isEqualTo(1);
    }

    @Test
    void selectionMatchingNothingProcessesNothing() {
        // Given
        given(index(repo("org", "app", false)), List.of(project("p1", "app", "org", "app")));

        // When
        service().run(new HousekeepingSelection(Set.of("unknown"), Set.of())).await().atMost(Duration.ofSeconds(10));

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
            List.of()
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
}
