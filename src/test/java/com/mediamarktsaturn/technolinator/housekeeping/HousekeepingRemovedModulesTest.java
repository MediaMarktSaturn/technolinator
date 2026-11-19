package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.currentStates;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.inactive;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.known;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.settings;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.uploadedAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HousekeepingRemovedModulesTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T02:00:00Z");
    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", STARTED, false);
    private static final HousekeepingRun DRY_RUN = new HousekeepingRun("run-2", STARTED, true);

    private DependencyTrackClient dtrack;
    private GitHubRepositoryConfig config;
    private SimpleMeterRegistry meters;
    private Map<String, DependencyTrackProject> current;

    @BeforeEach
    void setUp() {
        dtrack = mock(DependencyTrackClient.class);
        config = mock(GitHubRepositoryConfig.class);
        meters = new SimpleMeterRegistry();
        current = currentStates(dtrack);
        when(dtrack.archiveProject(anyString())).thenReturn(Uni.createFrom().item(true));
        when(dtrack.reactivateProject(anyString())).thenReturn(Uni.createFrom().item(true));
    }

    private HousekeepingWorker worker(boolean removedModules, int maxPerRun, boolean reactivate) {
        var worker = new HousekeepingWorker(dtrack, mock(GitHubRepositoryTopics.class), config, meters, settings(
            HousekeepingWorker.Feature.off(),
            HousekeepingWorker.Feature.off(),
            removedModules ? HousekeepingWorker.Feature.on(maxPerRun) : HousekeepingWorker.Feature.off(),
            reactivate ? HousekeepingWorker.Feature.on(10) : HousekeepingWorker.Feature.off(),
            HousekeepingWorker.Feature.off()));
        worker.begin(RUN);
        worker.begin(DRY_RUN);
        return worker;
    }

    private HousekeepingWorker worker() {
        return worker(true, 10, false);
    }

    private boolean process(HousekeepingWorker worker, HousekeepingUnit unit) {
        return worker.process(known(current, unit)).await().atMost(Duration.ofSeconds(10));
    }

    private static TechnolinatorConfig module(String name, TechnolinatorConfig... children) {
        return new TechnolinatorConfig(null, null, new TechnolinatorConfig.ProjectConfig(name), null, null, null, null, null,
            children.length == 0 ? null : List.of(children), null);
    }

    private static TechnolinatorConfig root(TechnolinatorConfig... modules) {
        return new TechnolinatorConfig(null, null, null, null, null, null, null, null, List.of(modules), null);
    }

    private void stubConfig(TechnolinatorConfig configuration) throws IOException {
        when(config.config(Mockito.any())).thenReturn(Optional.ofNullable(configuration));
    }

    private double actions(String status) {
        return meters.counter("housekeeping_action", "action", HousekeepingWorker.SYNC_PROJECT_STATES, "status", status).count();
    }

    private static HousekeepingUnit unit(HousekeepingRun run, List<DependencyTrackProject> projects) {
        return HousekeepingUnit.of(run, repo("org", "app", false), projects);
    }

    private static List<DependencyTrackProject> projects(DependencyTrackProject... projects) {
        return List.of(projects);
    }

    @Test
    void reportsActionAsEnabled() {
        assertThat(worker().enabledActions()).containsExactly(HousekeepingWorker.SYNC_PROJECT_STATES);
        assertThat(worker(false, 10, false).enabled()).isFalse();
    }

    @Test
    void archivesChildrenWhoseModuleIsNotConfiguredAnymore() throws IOException {
        // Given
        stubConfig(root(module("first"), module("second")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("second", "app-second", "parent", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).archiveProject("removed");
        verify(dtrack, never()).archiveProject("parent");
        verify(dtrack, never()).archiveProject("first");
        verify(dtrack, never()).archiveProject("second");
        assertThat(actions("OK")).isEqualTo(1);
    }

    @Test
    void keepsEverythingDescribedByTheConfiguration() throws IOException {
        // Given
        stubConfig(root(module("first"), module("second")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("second", "app-second", "parent", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void namesNestedModulesLikeTheAnalysisDoes() throws IOException {
        // Given
        stubConfig(root(module("services", module("billing"), module("shipping"))));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("billing", "app-services-billing", "parent", "org", "app"),
            child("shipping", "app-SERVICES-Shipping", "parent", "org", "app"),
            child("intermediate", "app-services", "parent", "org", "app")));

        // When
        process(worker(), unit);

        // Then
        verify(dtrack).archiveProject("intermediate");
        verify(dtrack, never()).archiveProject("billing");
        verify(dtrack, never()).archiveProject("shipping");
    }

    @Test
    void archivesAllChildrenWithoutConfiguration() throws IOException {
        // Given
        stubConfig(null);
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("second", "app-second", "parent", "org", "app")));

        // When
        process(worker(), unit);

        // Then
        verify(dtrack).archiveProject("first");
        verify(dtrack).archiveProject("second");
        verify(dtrack, never()).archiveProject("parent");
    }

    @Test
    void archivesAllVersionsOfRemovedModules() throws IOException {
        // Given
        stubConfig(root(module("first"), module("second")));
        var otherVersion = new DependencyTrackProject("removed-v2", "app-removed", "v2", true,
            Optional.of("parent"), List.of(HousekeepingTestData.gitUrl("org", "app")), Optional.empty());
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            otherVersion));

        // When
        process(worker(), unit);

        // Then
        verify(dtrack).archiveProject("removed");
        verify(dtrack).archiveProject("removed-v2");
    }

    @Test
    void leavesParentAndAlreadyArchivedChildrenAlone() throws IOException {
        // Given
        stubConfig(root(module("first"), module("second")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            inactive(child("removed", "app-removed", "parent", "org", "app"))));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void leavesChildrenOfOtherRepositoriesAlone() throws IOException {
        // Given
        stubConfig(root(module("first")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("foreign", "app-foreign", "parent", "org", "other")));

        // When
        process(worker(), unit);

        // Then
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void doesNotReadConfigurationWithoutChildren() {
        // Given
        var unit = unit(RUN, projects(project("p", "app", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(config, dtrack);
    }

    @Test
    void doesNothingForArchivedRepositories() {
        // Given
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app")));

        // When
        process(worker(), unit);

        // Then
        verifyNoInteractions(config, dtrack);
    }

    @Test
    void failsWithoutArchivingIfConfigurationCantBeRead() throws IOException {
        // Given
        when(config.config(Mockito.any())).thenThrow(new IllegalStateException("invalid yaml"));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isFalse();
        verify(dtrack, never()).archiveProject(anyString());
        assertThat(actions("ERROR")).isEqualTo(1);
    }

    @Test
    void failsWithoutArchivingIfRepositoryCantBeLookedUp() throws IOException {
        // Given
        when(config.config(Mockito.any())).thenThrow(new IOException("boom"));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isFalse();
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void leavesRepositoriesAloneThatDisabledTheAnalysis() throws IOException {
        // Given
        stubConfig(new TechnolinatorConfig(false, null, null, null, null, null, null, null, null, null));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void onlyLogsInDryRunMode() throws IOException {
        // Given
        stubConfig(root(module("first")));
        var unit = unit(DRY_RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void ignoresRepositoriesHavingProjectsUploadedSinceTheRunStarted() throws IOException {
        // Given
        stubConfig(root(module("first")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            uploadedAt(child("removed", "app-removed", "parent", "org", "app"), STARTED.plusSeconds(60))));

        // When
        process(worker(), unit);

        // Then
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void archivesChildrenBeforeParents() throws IOException {
        // Given
        stubConfig(root(module("first")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            child("removed-leaf", "app-removed-leaf", "removed", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        InOrder order = Mockito.inOrder(dtrack);
        order.verify(dtrack).archiveProject("removed-leaf");
        order.verify(dtrack).archiveProject("removed");
    }

    @Test
    void leavesObsoleteProjectsWithActiveDescendantsOfConfiguredModulesAlone() throws IOException {
        // Given
        stubConfig(root(module("first")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            child("first", "app-first", "removed", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void failsAndKeepsParentsActiveIfChildCantBeArchived() throws IOException {
        // Given
        stubConfig(root(module("first")));
        when(dtrack.archiveProject("removed-leaf")).thenReturn(Uni.createFrom().item(false));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            child("removed-leaf", "app-removed-leaf", "removed", "org", "app")));

        // When
        var success = process(worker(), unit);

        // Then
        assertThat(success).isFalse();
        verify(dtrack, never()).archiveProject("removed");
    }

    @Test
    void limitsArchivedProjectsPerRun() throws IOException {
        // Given
        stubConfig(root(module("first")));
        var worker = worker(true, 1, false);
        var first = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app")));
        var second = HousekeepingUnit.of(RUN, repo("org", "lib", false), List.of(
            project("lib-parent", "lib", "org", "lib"),
            child("lib-removed", "lib-removed", "lib-parent", "org", "lib")));

        // When
        process(worker, first);
        process(worker, second);

        // Then
        verify(dtrack).archiveProject("removed");
        verify(dtrack, never()).archiveProject("lib-removed");
    }

    @Test
    void reactivationLeavesRemovedModulesArchived() throws IOException {
        // Given
        stubConfig(root(module("first")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            inactive(child("removed", "app-removed", "parent", "org", "app"))));

        // When
        var success = process(worker(true, 10, true), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).reactivateProject(anyString());
    }

    @Test
    void reactivationStillRepairsConfiguredModules() throws IOException {
        // Given
        stubConfig(root(module("first"), module("second")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("first", "app-first", "parent", "org", "app"),
            inactive(child("second", "app-second", "parent", "org", "app")),
            inactive(child("removed", "app-removed", "parent", "org", "app"))));

        // When
        var success = process(worker(true, 10, true), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).reactivateProject("second");
        verify(dtrack, never()).reactivateProject("removed");
    }

    @Test
    void reactivationDoesntReadConfigurationIfRemovedModulesActionIsDisabled() {
        // Given
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            inactive(child("removed", "app-removed", "parent", "org", "app"))));

        // When
        var success = process(worker(false, 10, true), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).reactivateProject("removed");
        verifyNoInteractions(config);
    }

    @Test
    void readsConfigurationOnceForArchivingAndReactivating() throws IOException {
        // Given
        stubConfig(root(module("first"), module("second")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            inactive(child("second", "app-second", "parent", "org", "app"))));

        // When
        var success = process(worker(true, 10, true), unit);

        // Then
        assertThat(success).isTrue();
        verify(config, times(1)).config(Mockito.any());
        verify(dtrack).archiveProject("removed");
        verify(dtrack).reactivateProject("second");
        assertThat(actions("OK")).isEqualTo(1);
    }

    @Test
    void doesNotReactivateChildrenBelowAProjectArchivedAtTheSameTime() throws IOException {
        // Given
        stubConfig(root(module("first")));
        // "first" is configured, but below a removed module, which gets archived
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            inactive(child("first", "app-first", "removed", "org", "app"))));

        // When
        var success = process(worker(true, 10, true), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).archiveProject("removed");
        verify(dtrack, never()).reactivateProject(anyString());
    }

    @Test
    void reactivatesEvenIfArchivingRemovedModulesHitsTheLimit() throws IOException {
        // Given
        stubConfig(root(module("first"), module("second")));
        var unit = unit(RUN, projects(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            child("removed-too", "app-removed-too", "parent", "org", "app"),
            inactive(child("second", "app-second", "parent", "org", "app"))));

        // When
        var success = process(worker(true, 1, true), unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).archiveProject(anyString());
        verify(dtrack).reactivateProject("second");
    }
}
