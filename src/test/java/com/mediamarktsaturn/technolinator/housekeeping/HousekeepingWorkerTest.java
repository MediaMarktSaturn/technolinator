package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.UNKNOWN;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.inactive;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.uploadReferences;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.uploadedAt;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.withMetadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private GitHubRepositoryTopics topics;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() throws IOException {
        dtrack = mock(DependencyTrackClient.class);
        topics = mock(GitHubRepositoryTopics.class);
        meters = new SimpleMeterRegistry();
        when(dtrack.archiveProject(anyString())).thenReturn(Uni.createFrom().item(true));
        when(topics.topics(any())).thenReturn(List.of("java", "quarkus"));
    }

    private HousekeepingWorker worker(boolean archive, int maxPerRun) {
        return worker(archive, maxPerRun, Duration.ofSeconds(10));
    }

    private HousekeepingWorker worker(boolean archive, int maxPerRun, Duration timeout) {
        return worker(archive, maxPerRun, timeout, false, 10, false, 10);
    }

    private HousekeepingWorker metadataWorker(int maxPerRun) {
        return worker(false, 10, Duration.ofSeconds(10), true, maxPerRun, false, 10);
    }

    private HousekeepingWorker reactivateWorker(int maxPerRun) {
        return worker(false, 10, Duration.ofSeconds(10), false, 10, true, maxPerRun);
    }

    private HousekeepingWorker worker(boolean archive, int maxPerRun, Duration timeout, boolean updateMetadata, int maxMetadataPerRun,
                                      boolean reactivate, int maxReactivatedPerRun) {
        var worker = new HousekeepingWorker(dtrack, topics, meters, timeout, archive, maxPerRun, updateMetadata, maxMetadataPerRun,
            reactivate, maxReactivatedPerRun, false, 10);
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
        new HousekeepingWorker(dtrack, topics, meters, Duration.ofSeconds(1), true, 10, true, 10, true, 10, true, 10).end(RUN);
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

    private double metadataActions(String status) {
        return meters.counter("housekeeping_action", "action", HousekeepingWorker.UPDATE_PROJECT_METADATA, "status", status).count();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<String>> tagsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<DependencyTrackProject.Reference>> referencesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private void stubFetchAndUpdate(DependencyTrackProject... projects) {
        for (var project : projects) {
            when(dtrack.getProject(project.uuid())).thenReturn(Uni.createFrom().item(Optional.of(project)));
            when(dtrack.updateProjectReferencesAndTags(eq(project.uuid()), anyList(), anyList())).thenReturn(Uni.createFrom().item(true));
        }
    }

    @Test
    void reportsMetadataActionAsEnabled() {
        assertThat(metadataWorker(10).enabledActions()).containsExactly(HousekeepingWorker.UPDATE_PROJECT_METADATA);
    }

    @Test
    void addsReferencesAndTagsToProjectsLackingThem() {
        // Given
        var worker = metadataWorker(10);
        var plain = plainProject("p", "app");
        stubFetchAndUpdate(plain);
        var unit = HousekeepingUnit.of(RUN, repo("MyOrg", "app", false), List.of(plain));
        var references = referencesCaptor();
        var tags = tagsCaptor();

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verify(dtrack).updateProjectReferencesAndTags(eq("p"), references.capture(), tags.capture());
        assertThat(references.getValue()).containsExactlyElementsOf(uploadReferences("MyOrg", "app"));
        assertThat(tags.getValue()).containsExactly("owner=MyOrg", "java", "quarkus");
        assertThat(metadataActions("OK")).isEqualTo(1);
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void addsOwnerTagToProjectsHavingReferencesAlready() {
        // Given
        var worker = metadataWorker(10);
        var referenced = withMetadata(project("p", "app", "org", "app"), uploadReferences("org", "app"), List.of("java"));
        stubFetchAndUpdate(referenced);
        var tags = tagsCaptor();

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(referenced)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).updateProjectReferencesAndTags(eq("p"), anyList(), tags.capture());
        assertThat(tags.getValue()).containsExactly("owner=org", "java", "quarkus");
    }

    @Test
    void keepsTagsOfAnalysisAndReferencesOfOtherTypes() {
        // Given
        var worker = metadataWorker(10);
        var oldReferences = List.of(
            new DependencyTrackProject.Reference("vcs", "git://github.com/org/old.git"),
            new DependencyTrackProject.Reference("documentation", "https://docs.example.com", "the docs")
        );
        var existing = withMetadata(plainProject("p", "app"), oldReferences,
            List.of("Owner=OldOrg", "sbom-quality-score=8.1", "backstage.io/x=y", "obsolete-topic"));
        stubFetchAndUpdate(existing);
        var references = referencesCaptor();
        var tags = tagsCaptor();

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(existing)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).updateProjectReferencesAndTags(eq("p"), references.capture(), tags.capture());
        assertThat(references.getValue()).containsExactlyElementsOf(
            Stream.concat(uploadReferences("org", "app").stream(), Stream.of(oldReferences.get(1))).toList());
        assertThat(tags.getValue()).containsExactly("owner=org", "java", "quarkus", "sbom-quality-score=8.1", "backstage.io/x=y");
    }

    @Test
    void leavesProjectsHavingReferencesAndOwnerTagAlone() throws IOException {
        // Given
        var worker = metadataWorker(10);
        var complete = withMetadata(project("p", "app", "org", "app"), uploadReferences("org", "app"), List.of("Owner=org", "old-topic"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(complete)));

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
        verify(topics, never()).topics(any());
    }

    @Test
    void onlyTouchesProjectsDefinitelyBelongingToTheRepository() {
        // Given
        var worker = metadataWorker(10);
        var byName = plainProject("by-name", "app");
        var byReference = project("by-reference", "app-api", "org", "app");
        var childOfMatched = new DependencyTrackProject("child", "app-impl", "main", true, Optional.of("by-name"), List.of(), Optional.empty());
        // matching the name only in the loosest sense
        var onlyPrefix = plainProject("prefix", "app-other");
        var elsewhere = project("elsewhere", "app-lib", "stranger", "lib");
        var differentlyNamedChild = new DependencyTrackProject("unrelated", "service", "main", true, Optional.of("by-name"), List.of(), Optional.empty());
        stubFetchAndUpdate(byName, byReference, childOfMatched);

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false),
            List.of(byName, byReference, childOfMatched, onlyPrefix, elsewhere, differentlyNamedChild)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).updateProjectReferencesAndTags(eq("by-name"), anyList(), anyList());
        verify(dtrack).updateProjectReferencesAndTags(eq("by-reference"), anyList(), anyList());
        verify(dtrack).updateProjectReferencesAndTags(eq("child"), anyList(), anyList());
        verify(dtrack, never()).updateProjectReferencesAndTags(eq("prefix"), anyList(), anyList());
        verify(dtrack, never()).updateProjectReferencesAndTags(eq("elsewhere"), anyList(), anyList());
        verify(dtrack, never()).updateProjectReferencesAndTags(eq("unrelated"), anyList(), anyList());
    }

    @Test
    void doesNotTouchArchivedProjectsNorRepositoriesNorUnknownOnes() {
        // Given
        var worker = metadataWorker(10);

        // When
        var archivedProject = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(inactive(plainProject("p", "app")))));
        var archivedRepository = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(plainProject("p", "app"))));
        var unknownRepository = process(worker, HousekeepingUnit.withoutRepository(RUN, List.of(plainProject("p", "app")), UNKNOWN));
        var withoutProjects = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of()));

        // Then
        assertThat(archivedProject && archivedRepository && unknownRepository && withoutProjects).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void doesNotChangeAnythingInDryRunButLooksUpTopics() throws IOException {
        // Given
        var worker = metadataWorker(10);
        var repository = repo("org", "app", false);

        // When
        var success = process(worker, HousekeepingUnit.of(DRY_RUN, repository, List.of(plainProject("p", "app"))));

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
        verify(topics).topics(repository);
    }

    @Test
    void ignoresProjectsUpToDateWhenFetchedAgain() {
        // Given
        var worker = metadataWorker(10);
        var listed = plainProject("p", "app");
        var updatedMeanwhile = withMetadata(listed, uploadReferences("org", "app"), List.of("owner=org", "java", "quarkus"));
        when(dtrack.getProject("p")).thenReturn(Uni.createFrom().item(Optional.of(updatedMeanwhile)));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(listed)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).updateProjectReferencesAndTags(anyString(), anyList(), anyList());
    }

    @Test
    void ignoresProjectsDeletedOrArchivedWhenFetchedAgain() {
        // Given
        var worker = metadataWorker(10);
        var deleted = plainProject("deleted", "app");
        var archived = plainProject("archived", "app-api");
        when(dtrack.getProject("deleted")).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(dtrack.getProject("archived")).thenReturn(Uni.createFrom().item(Optional.of(inactive(archived))));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(deleted, withParent(archived, "deleted"))));

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).updateProjectReferencesAndTags(anyString(), anyList(), anyList());
    }

    @Test
    void reportsFailureToUpdateButContinuesWithOtherProjects() {
        // Given
        var worker = metadataWorker(10);
        var failing = plainProject("failing", "app");
        var working = project("working", "app-api", "org", "app");
        stubFetchAndUpdate(failing, working);
        when(dtrack.updateProjectReferencesAndTags(eq("failing"), anyList(), anyList())).thenReturn(Uni.createFrom().item(false));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(failing, working)));

        // Then
        assertThat(success).isFalse();
        verify(dtrack).updateProjectReferencesAndTags(eq("working"), anyList(), anyList());
        assertThat(metadataActions("ERROR")).isEqualTo(1);
    }

    @Test
    void reportsFailureToFetchTopicsWithoutChangingAnything() throws IOException {
        // Given
        var worker = metadataWorker(10);
        when(topics.topics(any())).thenThrow(new IOException("boom"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(plainProject("p", "app"))));

        // Then
        assertThat(success).isFalse();
        verifyNoInteractions(dtrack);
    }

    @Test
    void limitsMetadataUpdatesPerRun() {
        // Given
        var worker = metadataWorker(1);
        var first = plainProject("p1", "first");
        var second = plainProject("p2", "second");
        stubFetchAndUpdate(first, second);

        // When
        process(worker, HousekeepingUnit.of(RUN, repo("org", "first", false), List.of(first)));
        process(worker, HousekeepingUnit.of(RUN, repo("org", "second", false), List.of(second)));

        // Then
        verify(dtrack).updateProjectReferencesAndTags(eq("p1"), anyList(), anyList());
        verify(dtrack, never()).updateProjectReferencesAndTags(eq("p2"), anyList(), anyList());
    }

    private double reactivationActions(String status) {
        return meters.counter("housekeeping_action", "action", HousekeepingWorker.REACTIVATE_PROJECTS, "status", status).count();
    }

    private void stubReactivation() {
        when(dtrack.reactivateProject(anyString())).thenReturn(Uni.createFrom().item(true));
    }

    private static DependencyTrackProject withVersion(DependencyTrackProject project, String version) {
        return new DependencyTrackProject(project.uuid(), project.name(), version, project.active(), project.parentUuid(),
            project.repositoryUrls(), project.lastBomImport());
    }

    @Test
    void reportsReactivationActionAsEnabled() {
        assertThat(reactivateWorker(10).enabledActions()).containsExactly(HousekeepingWorker.REACTIVATE_PROJECTS);
    }

    @Test
    void reactivatesArchivedProjectOfActiveRepository() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        var archived = inactive(project("p", "app", "org", "app"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(archived)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).reactivateProject("p");
        verify(dtrack, never()).archiveProject(anyString());
        assertThat(reactivationActions("OK")).isEqualTo(1);
    }

    @Test
    void reactivatesProjectMatchedByNameOnly() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(inactive(plainProject("p", "app")))));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).reactivateProject("p");
    }

    @Test
    void leavesArchivedVersionsAloneIfAnotherVersionOfTheSameNameIsActive() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        var current = withVersion(plainProject("current", "app"), "v2");
        var old = inactive(withVersion(plainProject("old", "app"), "v1"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(current, old)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).reactivateProject(anyString());
    }

    @Test
    void reactivatesOnlyTheLatestUploadOfSeveralArchivedVersions() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        var oldest = uploadedAt(inactive(withVersion(plainProject("oldest", "app"), "v1")), STARTED.minus(Duration.ofDays(30)));
        var latest = uploadedAt(inactive(withVersion(plainProject("latest", "app"), "v3")), STARTED.minus(Duration.ofDays(2)));
        var middle = uploadedAt(inactive(withVersion(plainProject("middle", "app"), "v2")), STARTED.minus(Duration.ofDays(10)));
        var neverUploaded = inactive(withVersion(plainProject("never", "app"), "v0"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(oldest, latest, middle, neverUploaded)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).reactivateProject("latest");
        verify(dtrack, never()).reactivateProject("oldest");
        verify(dtrack, never()).reactivateProject("middle");
        verify(dtrack, never()).reactivateProject("never");
    }

    @Test
    void leavesSeveralArchivedVersionsAloneIfNoneHasBeenUploaded() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        var v1 = inactive(withVersion(plainProject("v1", "app"), "v1"));
        var v2 = inactive(withVersion(plainProject("v2", "app"), "v2"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(v1, v2)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).reactivateProject(anyString());
    }

    @Test
    void reactivatesParentsBeforeChildren() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        var parent = inactive(project("parent", "app", "org", "app"));
        var kid = inactive(child("kid", "app-api", "parent", "org", "app"));
        var grandKid = inactive(child("grandkid", "app-impl", "kid", "org", "app"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(grandKid, kid, parent)));

        // Then
        assertThat(success).isTrue();
        InOrder order = Mockito.inOrder(dtrack);
        order.verify(dtrack).reactivateProject("parent");
        order.verify(dtrack).reactivateProject("kid");
        order.verify(dtrack).reactivateProject("grandkid");
    }

    @Test
    void leavesChildrenArchivedIfTheirParentStaysArchived() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        // the parent is superseded by an active version, so it stays archived and so do its children
        var current = withVersion(project("current", "app", "org", "app"), "v2");
        var oldParent = inactive(withVersion(project("old-parent", "app", "org", "app"), "v1"));
        var kid = inactive(child("kid", "app-api", "old-parent", "org", "app"));
        var grandKid = inactive(child("grandkid", "app-impl", "kid", "org", "app"));
        // children of an active parent are fine
        var sibling = inactive(child("sibling", "app-web", "current", "org", "app"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(current, oldParent, kid, grandKid, sibling)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).reactivateProject("sibling");
        verify(dtrack, never()).reactivateProject("old-parent");
        verify(dtrack, never()).reactivateProject("kid");
        verify(dtrack, never()).reactivateProject("grandkid");
    }

    @Test
    void doesNotReactivateProjectsOfArchivedOrUnknownRepositories() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        var archived = inactive(plainProject("p", "app"));

        // When
        var archivedRepository = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(archived)));
        var unknownRepository = process(worker, HousekeepingUnit.withoutRepository(RUN, List.of(archived), UNKNOWN));
        var goneRepository = process(worker, HousekeepingUnit.withoutRepository(RUN, List.of(archived), GONE));

        // Then
        assertThat(archivedRepository && unknownRepository && goneRepository).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void doesNotReactivateProjectsNotDefinitelyBelongingToTheRepository() {
        // Given
        var worker = reactivateWorker(10);
        stubReactivation();
        var onlyPrefix = inactive(plainProject("prefix", "app-other"));
        var elsewhere = inactive(project("elsewhere", "app-lib", "stranger", "lib"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(onlyPrefix, elsewhere)));

        // Then
        assertThat(success).isTrue();
        verify(dtrack, never()).reactivateProject(anyString());
    }

    @Test
    void doesNotReactivateAnythingInDryRun() {
        // Given
        var worker = reactivateWorker(10);

        // When
        var success = process(worker, HousekeepingUnit.of(DRY_RUN, repo("org", "app", false), List.of(inactive(plainProject("p", "app")))));

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void reportsFailureToReactivate() {
        // Given
        var worker = reactivateWorker(10);
        when(dtrack.reactivateProject("p")).thenReturn(Uni.createFrom().item(false));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(inactive(plainProject("p", "app")))));

        // Then
        assertThat(success).isFalse();
        assertThat(reactivationActions("ERROR")).isEqualTo(1);
    }

    @Test
    void doesNotReactivateChildrenIfParentFailed() {
        // Given
        var worker = reactivateWorker(10);
        when(dtrack.reactivateProject("parent")).thenReturn(Uni.createFrom().item(false));
        var parent = inactive(project("parent", "app", "org", "app"));
        var kid = inactive(child("kid", "app-api", "parent", "org", "app"));

        // When
        var success = process(worker, HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(parent, kid)));

        // Then
        assertThat(success).isFalse();
        verify(dtrack, never()).reactivateProject("kid");
    }

    @Test
    void limitsReactivationsPerRun() {
        // Given
        var worker = reactivateWorker(1);
        stubReactivation();

        // When
        process(worker, HousekeepingUnit.of(RUN, repo("org", "first", false), List.of(inactive(plainProject("p1", "first")))));
        process(worker, HousekeepingUnit.of(RUN, repo("org", "second", false), List.of(inactive(plainProject("p2", "second")))));

        // Then
        verify(dtrack).reactivateProject("p1");
        verify(dtrack, never()).reactivateProject("p2");
    }

    private HousekeepingWorker followWorker(int maxPerRun) {
        var worker = new HousekeepingWorker(dtrack, topics, meters, Duration.ofSeconds(10), false, 10, false, 10, false, 10, true, maxPerRun);
        worker.begin(RUN);
        worker.begin(DRY_RUN);
        return worker;
    }

    private double renameActions(String status) {
        return meters.counter("housekeeping_action", "action", HousekeepingWorker.FOLLOW_RENAMED_REPOSITORIES, "status", status).count();
    }

    private static HousekeepingUnit renamedUnit(HousekeepingRun run, DependencyTrackProject... projects) {
        return HousekeepingUnit.ofRenamedRepository(run, List.of(projects), new RepositoryReference("github.com", "org", "old"), repo("org", "new", false));
    }

    private void stubRename(DependencyTrackProject project, DependencyTrackClient.RenameStatus status) {
        when(dtrack.renameProjectOfRepository(eq(project), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Uni.createFrom().item(status));
    }

    @Test
    void reportsFollowActionAsEnabled() {
        assertThat(followWorker(10).enabledActions()).containsExactly(HousekeepingWorker.FOLLOW_RENAMED_REPOSITORIES);
    }

    @Test
    void renamesProjectsOfRenamedRepository() {
        // Given
        var worker = followWorker(10);
        var parent = project("parent", "old", "org", "old");
        var module = child("module", "old-api", "parent", "org", "old");
        stubRename(parent, DependencyTrackClient.RenameStatus.RENAMED);
        stubRename(module, DependencyTrackClient.RenameStatus.RENAMED);

        // When
        var success = process(worker, renamedUnit(RUN, parent, module));

        // Then
        assertThat(success).isTrue();
        verify(dtrack).renameProjectOfRepository(parent, "old", "new", "git://github.com/org/old.git", "https://github.com/org/old",
            "git://github.com/org/new.git", "https://github.com/org/new");
        verify(dtrack).renameProjectOfRepository(eq(module), eq("old"), eq("new"), anyString(), anyString(), anyString(), anyString());
        verify(dtrack, never()).archiveProject(anyString());
        assertThat(renameActions("OK")).isEqualTo(1);
    }

    @Test
    void doesNotApplyToOtherUnits() {
        // Given
        var worker = followWorker(10);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(worker, unit);

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
    }

    @Test
    void archivesProjectsThatCanNotBeRenamedBecauseTheNameIsTaken() {
        // Given
        var worker = followWorker(10);
        var parent = project("parent", "old", "org", "old");
        var module = child("module", "old-api", "parent", "org", "old");
        var oldVersion = inactive(withVersion(project("archived", "old", "org", "old"), "v0"));
        stubRename(parent, DependencyTrackClient.RenameStatus.CONFLICT);
        stubRename(module, DependencyTrackClient.RenameStatus.CONFLICT);
        stubRename(oldVersion, DependencyTrackClient.RenameStatus.CONFLICT);
        when(dtrack.archiveProject(anyString())).thenReturn(Uni.createFrom().item(true));

        // When
        var success = process(worker, renamedUnit(RUN, parent, module, oldVersion));

        // Then
        assertThat(success).isTrue();
        InOrder order = Mockito.inOrder(dtrack);
        order.verify(dtrack).archiveProject("module");
        order.verify(dtrack).archiveProject("parent");
        verify(dtrack, never()).archiveProject("archived");
    }

    @Test
    void doesNotArchiveIfRenamingFailsForOtherReasons() {
        // Given
        var worker = followWorker(10);
        var failing = project("p", "old", "org", "old");
        stubRename(failing, DependencyTrackClient.RenameStatus.FAILED);

        // When
        var success = process(worker, renamedUnit(RUN, failing));

        // Then
        assertThat(success).isFalse();
        verify(dtrack, never()).archiveProject(anyString());
        assertThat(renameActions("ERROR")).isEqualTo(1);
    }

    @Test
    void reportsFailureOfClientWhenRenaming() {
        // Given
        var worker = followWorker(10);
        var failing = project("p", "old", "org", "old");
        when(dtrack.renameProjectOfRepository(eq(failing), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Uni.createFrom().failure(new IllegalStateException("boom")));

        // When
        var success = process(worker, renamedUnit(RUN, failing));

        // Then
        assertThat(success).isFalse();
        verify(dtrack, never()).archiveProject(anyString());
    }

    @Test
    void doesNotRenameAnythingInDryRun() {
        // Given
        var worker = followWorker(10);

        // When
        var success = process(worker, renamedUnit(DRY_RUN, project("p", "old", "org", "old")));

        // Then
        assertThat(success).isTrue();
        verifyNoInteractions(dtrack);
        assertThat(renameActions("OK")).isEqualTo(1);
    }

    @Test
    void limitsRenamedProjectsPerRun() {
        // Given
        var worker = followWorker(1);
        var first = project("p1", "old", "org", "old");
        var second = project("p2", "old", "org", "old");
        stubRename(first, DependencyTrackClient.RenameStatus.RENAMED);
        stubRename(second, DependencyTrackClient.RenameStatus.RENAMED);

        // When
        process(worker, renamedUnit(RUN, first));
        process(worker, renamedUnit(RUN, second));

        // Then
        verify(dtrack).renameProjectOfRepository(eq(first), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(dtrack, never()).renameProjectOfRepository(eq(second), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static DependencyTrackProject withParent(DependencyTrackProject project, String parentUuid) {
        return new DependencyTrackProject(project.uuid(), project.name(), project.version(), project.active(), Optional.of(parentUuid),
            project.repositoryUrls(), project.lastBomImport());
    }
}
