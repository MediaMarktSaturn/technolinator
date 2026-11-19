package com.mediamarktsaturn.technolinator.housekeeping;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ACTIVE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ARCHIVED;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.UNKNOWN;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.inactive;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HousekeepingUnitTest {

    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", Instant.parse("2026-01-01T02:00:00Z"), false);

    @Test
    void stateFollowsRepositoryArchivedFlag() {
        assertThat(HousekeepingUnit.of(RUN, repo("org", "app", false), List.of()).repositoryState()).isEqualTo(ACTIVE);
        assertThat(HousekeepingUnit.of(RUN, repo("org", "app", true), List.of()).repositoryState()).isEqualTo(ARCHIVED);
    }

    @Test
    void goneOrArchivedIsOnlyTrueIfCertain() {
        var projects = List.of(project("p", "app", "org", "app"));
        assertThat(HousekeepingUnit.of(RUN, repo("org", "app", false), projects).repositoryGoneOrArchived()).isFalse();
        assertThat(HousekeepingUnit.of(RUN, repo("org", "app", true), projects).repositoryGoneOrArchived()).isTrue();
        assertThat(HousekeepingUnit.withoutRepository(RUN, projects, GONE).repositoryGoneOrArchived()).isTrue();
        assertThat(HousekeepingUnit.withoutRepository(RUN, projects, UNKNOWN).repositoryGoneOrArchived()).isFalse();
    }

    @Test
    void rejectsInconsistentUnits() {
        var projects = List.of(project("p", "app", "org", "app"));
        assertThatThrownBy(() -> HousekeepingUnit.withoutRepository(RUN, projects, ACTIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HousekeepingUnit.withoutRepository(RUN, projects, ARCHIVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HousekeepingUnit(RUN, Optional.of(repo("org", "app", false)), projects, GONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HousekeepingUnit(RUN, Optional.of(repo("org", "app", false)), projects, UNKNOWN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyUnit() {
        assertThatThrownBy(() -> HousekeepingUnit.withoutRepository(RUN, List.of(), GONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activeProjectsExcludeArchivedOnes() {
        // Given
        var active = project("a", "app", "org", "app");
        var archived = inactive(project("b", "app", "org", "app"));

        // When
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(active, archived));

        // Then
        assertThat(unit.activeProjects()).containsExactly(active);
    }

    @Test
    void ordersChildrenBeforeParents() {
        // Given
        var parent = project("parent", "app", "org", "app");
        var module = child("module", "app-api", "parent", "org", "app");
        var submodule = child("submodule", "app-api-impl", "module", "org", "app");
        var otherModule = child("other", "app-web", "parent", "org", "app");
        var standalone = project("standalone", "app-tool", "org", "app");

        // When
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(parent, module, submodule, otherModule, standalone));
        var waves = unit.groupChildrenFirst(unit.activeProjects());

        // Then
        assertThat(waves).hasSize(3);
        assertThat(waves.get(0)).containsExactly(submodule);
        assertThat(waves.get(1)).containsExactlyInAnyOrder(module, otherModule);
        assertThat(waves.get(2)).containsExactlyInAnyOrder(parent, standalone);
    }

    @Test
    void ordersByDepthEvenIfAnArchivedProjectIsInBetween() {
        // Given
        var parent = project("parent", "app", "org", "app");
        var archivedModule = inactive(child("module", "app-api", "parent", "org", "app"));
        var submodule = child("submodule", "app-api-impl", "module", "org", "app");

        // When
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(parent, archivedModule, submodule));
        var waves = unit.groupChildrenFirst(unit.activeProjects());

        // Then
        assertThat(waves).containsExactly(List.of(submodule), List.of(parent));
    }

    @Test
    void survivesCyclicHierarchy() {
        // Given
        var a = child("a", "app", "b", "org", "app");
        var b = child("b", "app-b", "a", "org", "app");

        // When
        var unit = HousekeepingUnit.withoutRepository(RUN, List.of(a, b), GONE);
        var waves = unit.groupChildrenFirst(unit.activeProjects());

        // Then
        assertThat(waves.stream().mapToInt(List::size).sum()).isEqualTo(2);
    }
}
