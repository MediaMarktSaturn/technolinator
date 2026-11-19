package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.Change;
import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.ConfiguredModules;
import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.Reason;
import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.Rules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.UNKNOWN;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.inactive;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectStatePlannerTest {

    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", Instant.parse("2026-01-01T02:00:00Z"), false);
    private static final Rules ALL = new Rules(true, true, true);
    private static final ConfiguredModules ONLY_FIRST = new ConfiguredModules(Set.of("app", "app-first"), true);

    private static List<String> plan(HousekeepingUnit unit, Rules rules, Optional<ConfiguredModules> modules) {
        return ProjectStatePlanner.plan(unit, rules, modules).stream()
            .map(change -> change.reason() + ":" + change.project().uuid())
            .toList();
    }

    @Test
    void archivesAllActiveProjectsOfArchivedOrGoneRepositories() {
        var archived = HousekeepingUnit.of(RUN, repo("org", "app", true), List.of(
            project("p", "app", "org", "app"), inactive(project("old", "app", "org", "app"))));
        var gone = HousekeepingUnit.withoutRepository(RUN, List.of(project("g", "gone", "org", "gone")), GONE);

        assertThat(plan(archived, ALL, Optional.empty())).containsExactly("REPOSITORY_ARCHIVED:p");
        assertThat(plan(gone, ALL, Optional.empty())).containsExactly("REPOSITORY_GONE:g");
        assertThat(plan(gone, new Rules(false, true, true), Optional.empty())).isEmpty();
    }

    @Test
    void leavesUnknownUnitsAlone() {
        var unit = HousekeepingUnit.withoutRepository(RUN, List.of(project("p", "app", "org", "app")), UNKNOWN);

        assertThat(ProjectStatePlanner.mightApply(unit, ALL)).isFalse();
        assertThat(plan(unit, ALL, Optional.empty())).isEmpty();
    }

    @Test
    void plansArchivingAndReactivatingTogether() {
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            inactive(child("first", "app-first", "parent", "org", "app"))));

        assertThat(ProjectStatePlanner.needsConfiguration(unit, ALL)).isTrue();
        assertThat(plan(unit, ALL, Optional.of(ONLY_FIRST))).containsExactly("MODULE_REMOVED:removed", "REPOSITORY_ACTIVE:first");
    }

    @Test
    void doesNotReactivateChildrenOfProjectsBeingArchived() {
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(
            project("parent", "app", "org", "app"),
            child("removed", "app-removed", "parent", "org", "app"),
            inactive(child("first", "app-first", "removed", "org", "app"))));

        assertThat(plan(unit, ALL, Optional.of(ONLY_FIRST))).containsExactly("MODULE_REMOVED:removed");
    }

    @Test
    void keepsRemovedModulesArchived() {
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(
            project("parent", "app", "org", "app"),
            inactive(child("removed", "app-removed", "parent", "org", "app"))));

        assertThat(ProjectStatePlanner.needsConfiguration(unit, ALL)).isTrue();
        assertThat(plan(unit, ALL, Optional.of(ONLY_FIRST))).isEmpty();
        // without the rule for removed modules, children are reactivated as any other project
        assertThat(ProjectStatePlanner.needsConfiguration(unit, new Rules(true, false, true))).isFalse();
        assertThat(plan(unit, new Rules(true, false, true), Optional.empty())).containsExactly("REPOSITORY_ACTIVE:removed");
    }

    @Test
    void keepsRemovedModulesArchivedEvenIfTheAnalysisIsDisabled() {
        var disabled = new ConfiguredModules(Set.of("app", "app-first"), false);
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(
            project("parent", "app", "org", "app"),
            child("active-removed", "app-gone", "parent", "org", "app"),
            inactive(child("removed", "app-removed", "parent", "org", "app")),
            inactive(child("first", "app-first", "parent", "org", "app"))));

        // nothing is archived for a repository disabling the analysis, but removed modules aren't reactivated either
        assertThat(plan(unit, ALL, Optional.of(disabled))).containsExactly("REPOSITORY_ACTIVE:first");
    }

    @Test
    void doesNotNeedConfigurationWithoutChildren() {
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(
            project("p", "app", "org", "app"), inactive(project("old", "app-old", "org", "app"))));

        assertThat(ProjectStatePlanner.needsConfiguration(unit, ALL)).isFalse();
        assertThat(plan(unit, ALL, Optional.empty())).containsExactly("REPOSITORY_ACTIVE:old");
    }

    @Test
    void refusesToPlanWithoutRequiredConfiguration() {
        var unit = HousekeepingUnit.of(RUN, repo("org", "app", false), List.of(
            project("parent", "app", "org", "app"), child("module", "app-api", "parent", "org", "app")));

        assertThatThrownBy(() -> ProjectStatePlanner.plan(unit, ALL, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonsTellTheChange() {
        assertThat(new Change(project("p", "app", "org", "app"), Reason.REPOSITORY_ACTIVE).archive()).isFalse();
        assertThat(Reason.SUPERSEDED.archive).isTrue();
        assertThat(Reason.MODULE_REMOVED.logValue()).isEqualTo("module_removed");
    }
}
