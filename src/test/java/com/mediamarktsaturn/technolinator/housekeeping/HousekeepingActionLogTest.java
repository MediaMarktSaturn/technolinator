package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.housekeeping.HousekeepingWorker.Feature;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.currentStates;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.inactive;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.known;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The structured log lines of the housekeeping actions, see {@link HousekeepingActionLog}
 */
class HousekeepingActionLogTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T02:00:00Z");
    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", STARTED, false);
    private static final HousekeepingRun DRY_RUN = new HousekeepingRun("run-2", STARTED, true);

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private DependencyTrackClient dtrack;
    private GitHubRepositoryTopics topics;
    private GitHubRepositoryConfig config;
    private Map<String, DependencyTrackProject> current;
    private HousekeepingWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        Logger.getLogger("").addHandler(handler);
        dtrack = mock(DependencyTrackClient.class);
        topics = mock(GitHubRepositoryTopics.class);
        config = mock(GitHubRepositoryConfig.class);
        current = currentStates(dtrack);
        when(topics.topics(any())).thenReturn(List.of("java"));
        // the archiving of removed modules is covered separately, as it would archive all children without configuration
        worker = worker(settings(Feature.on(10), Feature.on(10), Feature.off(), Feature.on(10), Feature.on(10)), Duration.ofSeconds(10));
    }

    private HousekeepingWorker worker(HousekeepingWorker.Settings settings, Duration timeout) {
        var worker = new HousekeepingWorker(dtrack, topics, config, new SimpleMeterRegistry(), new HousekeepingWorker.Settings(timeout,
            settings.followRenamedRepositories(), settings.archiveOrphanedProjects(), settings.archiveRemovedModules(),
            settings.reactivateProjects(), settings.updateProjectMetadata()));
        worker.begin(RUN);
        worker.begin(DRY_RUN);
        return worker;
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").removeHandler(handler);
    }

    private List<String> actionLines() {
        return messages.stream().filter(message -> message != null && message.startsWith(HousekeepingActionLog.TAG)).toList();
    }

    private boolean process(HousekeepingUnit unit) {
        return worker.process(known(current, unit)).await().atMost(Duration.ofSeconds(10));
    }

    @Test
    void logsEveryArchivedProject() {
        // Given
        when(dtrack.archiveProject(anyString())).thenReturn(Uni.createFrom().item(true));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            project("parent", "app", "org", "app"),
            child("module", "app-api", "parent", "org", "app"),
            inactive(project("old", "app", "org", "app"))
        ));

        // When
        process(unit);

        // Then
        assertThat(actionLines()).containsExactlyInAnyOrder(
            "HOUSEKEEPING_ACTION action=sync_project_states status=done repository=org/app repository_state=archived project=parent name=app version=main change=archive reason=repository_archived",
            "HOUSEKEEPING_ACTION action=sync_project_states status=done repository=org/app repository_state=archived project=module name=app-api version=main change=archive reason=repository_archived"
        );
    }

    @Test
    void logsProjectsOfGoneRepositoryWithoutRepository() {
        // Given
        when(dtrack.archiveProject(anyString())).thenReturn(Uni.createFrom().item(true));
        var unit = HousekeepingUnit.withoutRepository(RUN, List.of(project("p", "app", "org", "app")), GONE);

        // When
        process(unit);

        // Then
        assertThat(actionLines()).containsExactly(
            "HOUSEKEEPING_ACTION action=sync_project_states status=done repository=- repository_state=gone project=p name=app version=main change=archive reason=repository_gone");
    }

    @Test
    void logsWhatWouldBeDoneInDryRun() {
        // Given
        var unit = HousekeepingUnit.of(DRY_RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        process(unit);

        // Then
        assertThat(actionLines()).containsExactly(
            "HOUSEKEEPING_ACTION action=sync_project_states status=dry_run repository=org/app repository_state=archived project=p name=app version=main change=archive reason=repository_archived");
    }

    @Test
    void logsFailedProjectOnlyOnce() {
        // Given
        when(dtrack.archiveProject("module")).thenReturn(Uni.createFrom().item(false));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            project("parent", "app", "org", "app"),
            child("module", "app-api", "parent", "org", "app")
        ));

        // When
        var success = process(unit);

        // Then
        assertThat(success).isFalse();
        assertThat(actionLines()).containsExactly(
            "HOUSEKEEPING_ACTION action=sync_project_states status=failed repository=org/app repository_state=archived project=module name=app-api version=main change=archive reason=repository_archived");
    }

    @Test
    void logsFailingClientForProject() {
        // Given
        when(dtrack.archiveProject("p")).thenReturn(Uni.createFrom().failure(new IllegalStateException("boom")));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(unit);

        // Then
        assertThat(success).isFalse();
        assertThat(actionLines()).containsExactly(
            "HOUSEKEEPING_ACTION action=sync_project_states status=failed repository=org/app repository_state=archived project=p name=app version=main change=archive reason=repository_archived");
    }

    @Test
    void logsFailureOfUnitNotRelatedToProject() {
        // Given
        worker = worker(settings(Feature.off(), Feature.on(10), Feature.off(), Feature.off(), Feature.off()), Duration.ofMillis(200));
        when(dtrack.archiveProject("p")).thenReturn(Uni.createFrom().nothing());
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(project("p", "app", "org", "app")));

        // When
        var success = process(unit);

        // Then
        assertThat(success).isFalse();
        assertThat(actionLines()).containsExactly(
            "HOUSEKEEPING_ACTION action=sync_project_states status=failed repository=org/app repository_state=archived project=- name=- version=-");
    }

    @Test
    void logsArchivedProjectsOfRemovedModules() throws IOException {
        // Given
        worker = worker(settings(Feature.off(), Feature.off(), Feature.on(10), Feature.off(), Feature.off()), Duration.ofSeconds(10));
        when(config.config(any())).thenReturn(Optional.empty());
        when(dtrack.archiveProject(anyString())).thenReturn(Uni.createFrom().item(true));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(
            project("parent", "app", "org", "app"),
            child("module", "app-api", "parent", "org", "app")
        ));

        // When
        process(unit);

        // Then
        assertThat(actionLines()).containsExactly(
            "HOUSEKEEPING_ACTION action=sync_project_states status=done repository=org/app repository_state=active project=module name=app-api version=main change=archive reason=module_removed");
    }

    @Test
    void logsReactivatedProjects() {
        // Given
        when(dtrack.reactivateProject(anyString())).thenReturn(Uni.createFrom().item(true));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(inactive(project("p", "app", "org", "app"))));

        // When
        process(unit);

        // Then
        assertThat(actionLines()).contains(
            "HOUSEKEEPING_ACTION action=sync_project_states status=done repository=org/app repository_state=active project=p name=app version=main change=reactivate reason=repository_active");
    }

    @Test
    void logsUpdatedAndFailedMetadata() {
        // Given
        var updated = plainProject("updated", "app");
        var failing = child("failing", "app-api", "updated", "org", "app");
        for (var project : List.of(updated, failing)) {
            when(dtrack.getProject(project.uuid())).thenReturn(Uni.createFrom().item(Optional.of(project)));
        }
        when(dtrack.updateProjectReferencesAndTags(eq("updated"), anyList(), anyList())).thenReturn(Uni.createFrom().item(true));
        when(dtrack.updateProjectReferencesAndTags(eq("failing"), anyList(), anyList())).thenReturn(Uni.createFrom().item(false));
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(updated, failing));

        // When
        var success = process(unit);

        // Then
        assertThat(success).isFalse();
        assertThat(actionLines()).containsExactlyInAnyOrder(
            "HOUSEKEEPING_ACTION action=update_project_metadata status=done repository=org/app repository_state=active project=updated name=app version=main",
            "HOUSEKEEPING_ACTION action=update_project_metadata status=failed repository=org/app repository_state=active project=failing name=app-api version=main"
        );
    }

    @Test
    void logsRenamedProjectsAndSupersededOnes() {
        // Given
        var renamed = project("renamed", "old", "org", "old");
        var superseded = child("superseded", "old-api", "renamed", "org", "old");
        when(dtrack.renameProjectOfRepository(eq(renamed), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Uni.createFrom().item(DependencyTrackClient.RenameStatus.RENAMED));
        when(dtrack.renameProjectOfRepository(eq(superseded), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Uni.createFrom().item(DependencyTrackClient.RenameStatus.CONFLICT));
        when(dtrack.archiveProject("superseded")).thenReturn(Uni.createFrom().item(true));
        var unit = HousekeepingUnit.ofRenamedRepository(RUN, List.of(renamed, superseded),
            new RepositoryReference("github.com", "org", "old"), repo("org", "new", false));

        // When
        var success = process(unit);

        // Then
        assertThat(success).isTrue();
        assertThat(actionLines()).containsExactlyInAnyOrder(
            "HOUSEKEEPING_ACTION action=follow_renamed_repositories status=done repository=org/new repository_state=moved project=renamed name=old version=main change=rename new_name=new",
            "HOUSEKEEPING_ACTION action=follow_renamed_repositories status=done repository=org/new repository_state=moved project=superseded name=old-api version=main change=archive reason=superseded"
        );
    }

    @Test
    void logsWhatWouldBeRenamedInDryRun() {
        // Given
        var unit = HousekeepingUnit.ofRenamedRepository(DRY_RUN, List.of(child("module", "old-api", "parent", "org", "old")),
            new RepositoryReference("github.com", "org", "old"), repo("org", "new", false));

        // When
        process(unit);

        // Then
        assertThat(actionLines()).containsExactly(
            "HOUSEKEEPING_ACTION action=follow_renamed_repositories status=dry_run repository=org/new repository_state=moved project=module name=old-api version=main change=rename new_name=new-api");
    }

    @Test
    void doesNotLogWhatWasNotActedOn() {
        // Given
        var complete = HousekeepingTestData.withMetadata(project("p", "app", "org", "app"), HousekeepingTestData.uploadReferences("org", "app"), List.of("owner=org"));

        // When
        process(HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(complete)));

        // Then
        assertThat(actionLines()).isEmpty();
    }
}
