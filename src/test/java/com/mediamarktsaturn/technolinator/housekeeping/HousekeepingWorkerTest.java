package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.UNKNOWN;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.inactive;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.uploadedAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HousekeepingWorkerTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T02:00:00Z");
    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", STARTED, false);
    private static final HousekeepingRun DRY_RUN = new HousekeepingRun("run-2", STARTED, true);

    private DependencyTrackClient dtrack;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        dtrack = mock(DependencyTrackClient.class);
        meters = new SimpleMeterRegistry();
        when(dtrack.archiveProject(anyString())).thenReturn(Uni.createFrom().item(true));
    }

    private HousekeepingWorker worker(boolean archive, int maxPerRun) {
        return worker(archive, maxPerRun, Duration.ofSeconds(10));
    }

    private HousekeepingWorker worker(boolean archive, int maxPerRun, Duration timeout) {
        var worker = new HousekeepingWorker(dtrack, meters, timeout, archive, maxPerRun);
        worker.begin(RUN);
        worker.begin(DRY_RUN);
        return worker;
    }

    private double actions(String status) {
        return meters.counter("housekeeping_action", "action", HousekeepingWorker.ARCHIVE_ORPHANED_PROJECTS, "status", status).count();
    }

    private static boolean process(HousekeepingWorker worker, HousekeepingUnit unit) {
        return worker.process(unit).await().atMost(Duration.ofSeconds(10));
    }

    @Test
    void reportsEnabledActions() {
        assertThat(worker(true, 10).enabled()).isTrue();
        assertThat(worker(true, 10).enabledActions()).containsExactly(HousekeepingWorker.ARCHIVE_ORPHANED_PROJECTS);
        assertThat(worker(false, 10).enabled()).isFalse();
        assertThat(worker(false, 10).enabledActions()).isEmpty();
    }

    @Test
    void doesNothingIfDisabled() {
        // Given
        var worker = worker(false, 10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void archivesProjectsOfArchivedRepository() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).archiveProject("p");
        assertThat(actions("OK")).isEqualTo(1);
    }

    @Test
    void archivesProjectsOfGoneRepositoryButNotInactiveOnes() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.withoutRepository(RUN, List.of(
            project("p", "app", "org", "app"),
            inactive(project("old", "app", "org", "app"))
        ), GONE);

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).archiveProject("p");
        verify(dtrack, never()).archiveProject("old");
    }

    @Test
    void archivesChildrenBeforeParents() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            project("parent", "app", "org", "app"),
            child("module", "app-api", "parent", "org", "app"),
            child("submodule", "app-api-impl", "module", "org", "app")
        ));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        InOrder order = Mockito.inOrder(dtrack);
        order.verify(dtrack).archiveProject("submodule");
        order.verify(dtrack).archiveProject("module");
        order.verify(dtrack).archiveProject("parent");
    }

    @Test
    void leavesProjectsOfActiveRepositoryAlone() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
        assertThat(actions("OK")).isZero();
    }

    @Test
    void leavesProjectsOfUnknownRepositoryAlone() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.withoutRepository(RUN, List.of(plainProject("p", "app")), UNKNOWN);

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void ignoresRepositoryWithoutProjects() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of());

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void doesNotChangeAnythingInDryRun() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.of(DRY_RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void skipsUnitWithProjectUploadedSinceRunStart() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            project("old", "app-old", "org", "app"),
            uploadedAt(project("fresh", "app", "org", "app"), STARTED.plusSeconds(1))
        ));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void archivesUnitWithProjectUploadedBeforeRunStart() {
        // Given
        var worker = worker(true, 10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            uploadedAt(project("p", "app", "org", "app"), STARTED.minusSeconds(60))
        ));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).archiveProject("p");
    }

    @Test
    void limitsArchivedProjectsPerRun() {
        // Given
        var worker = worker(true, 1);
        worker.begin(RUN);
        var first = HousekeepingUnit.of(RUN, repo("org", "first", true), List.of(project("p1", "first", "org", "first")));
        var second = HousekeepingUnit.of(RUN, repo("org", "second", true), List.of(project("p2", "second", "org", "second")));

        // When
        var firstSuccess = process(worker, first);
        var secondSuccess = process(worker, second);
        worker.end(RUN);

        // Then
        assertThat(firstSuccess).isTrue();
        assertThat(secondSuccess).isTrue();
        verify(dtrack).archiveProject("p1");
        verify(dtrack, never()).archiveProject("p2");
    }

    @Test
    void limitIsPerRun() {
        // Given
        var worker = worker(true, 1);
        var nextRun = new HousekeepingRun("run-3", STARTED.plusSeconds(86400), false);
        worker.begin(RUN);
        worker.begin(nextRun);

        // When
        process(worker, HousekeepingUnit.of(RUN, repo("org", "first", true), List.of(project("p1", "first", "org", "first"))));
        process(worker, HousekeepingUnit.of(nextRun, repo("org", "second", true), List.of(project("p2", "second", "org", "second"))));

        // Then
        verify(dtrack).archiveProject("p1");
        verify(dtrack).archiveProject("p2");
    }

    @Test
    void reportsFailureAndLeavesParentsOfFailedChildren() {
        // Given
        var worker = worker(true, 10);
        when(dtrack.archiveProject("module")).thenReturn(Uni.createFrom().item(false));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            project("parent", "app", "org", "app"),
            child("module", "app-api", "parent", "org", "app")
        ));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isFalse();
        verify(dtrack).archiveProject("module");
        verify(dtrack, never()).archiveProject("parent");
        assertThat(actions("ERROR")).isEqualTo(1);
        assertThat(actions("OK")).isZero();
    }

    @Test
    void reportsFailureOfClient() {
        // Given
        var worker = worker(true, 10);
        when(dtrack.archiveProject("p")).thenReturn(Uni.createFrom().failure(new IllegalStateException("boom")));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isFalse();
        assertThat(actions("ERROR")).isEqualTo(1);
    }

    @Test
    void failsActionTakingTooLong() {
        // Given
        var worker = worker(true, 10, Duration.ofMillis(200));
        when(dtrack.archiveProject("p")).thenReturn(Uni.createFrom().nothing());
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isFalse();
        assertThat(actions("ERROR")).isEqualTo(1);
    }

    @Test
    void endWithoutBeginIsHarmless() {
        new HousekeepingWorker(dtrack, meters, Duration.ofSeconds(1), true, 10).end(RUN);
    }

    @Test
    void skipsUnitsOfRunNotInProgress() {
        // Given
        var worker = worker(true, 10);
        worker.end(RUN);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
        assertThat(actions("OK")).isZero();
    }

    @Test
    void dryRunAppliesLimitWithoutChangingAnything() {
        // Given
        var worker = worker(true, 1);

        // When
        process(worker, HousekeepingUnit.of(DRY_RUN, repo("org", "first", true), List.of(
            project("p1", "first", "org", "first"), project("p2", "first-api", "org", "first"))));
        process(worker, HousekeepingUnit.of(DRY_RUN, repo("org", "second", true), List.of(project("p3", "second", "org", "second"))));

        // Then
        verifyNoInteractions(dtrack);
        assertThat(actions("OK")).isEqualTo(2);
    }
}
