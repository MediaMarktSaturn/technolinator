package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ACTIVE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ARCHIVED;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.UNKNOWN;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.inactive;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;

class UnitMatcherTest {

    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", Instant.parse("2026-01-01T02:00:00Z"), false);

    @Test
    void pairsRepositoryWithAllItsProjectsInAllVersions() {
        // Given
        var index = index("org", true, repo("org", "app", false));
        var parent = project("parent", "app", "org", "app");
        var module = child("module", "app-api", "parent", "org", "app");
        var oldVersion = inactive(project("old", "app", "org", "app"));

        // When
        var units = UnitMatcher.match(RUN, index, List.of(module, oldVersion, parent), Set.of());

        // Then
        assertThat(units).hasSize(1);
        var unit = units.getFirst();
        assertThat(unit.repository()).map(GitHubRepo::fullName).contains("org/app");
        assertThat(unit.projects()).containsExactlyInAnyOrder(parent, module, oldVersion);
        assertThat(unit.repositoryState()).isEqualTo(ACTIVE);
    }

    @Test
    void handsRepositoryWithoutProjectsToTheWorker() {
        // Given
        var index = index("org", true, repo("org", "never-analyzed", false), repo("org", "app", false));

        // When
        var units = UnitMatcher.match(RUN, index, List.of(project("p", "app", "org", "app")), Set.of());

        // Then
        assertThat(units).hasSize(2);
        var withoutProjects = units.stream().filter(unit -> unit.projects().isEmpty()).toList();
        assertThat(withoutProjects).hasSize(1);
        assertThat(withoutProjects.getFirst().repository()).map(GitHubRepo::fullName).contains("org/never-analyzed");
    }

    @Test
    void tellsArchivedRepositories() {
        var index = index("org", true, repo("org", "old", true));

        var units = UnitMatcher.match(RUN, index, List.of(project("p", "old", "org", "old")), Set.of());

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.repositoryState()).isEqualTo(ARCHIVED);
            assertThat(unit.repositoryGoneOrArchived()).isTrue();
        });
    }

    @Test
    void handsProjectsOfDeletedRepositoryToTheWorkerAsGone() {
        // Given
        var index = index("org", true, repo("org", "other", false));
        var parent = project("parent", "deleted", "org", "deleted");
        var module = child("module", "deleted-api", "parent", "org", "deleted");

        // When
        var units = UnitMatcher.match(RUN, index, List.of(parent, module), Set.of());

        // Then
        var orphans = units.stream().filter(unit -> unit.repository().isEmpty()).toList();
        assertThat(orphans).singleElement().satisfies(unit -> {
            assertThat(unit.repositoryState()).isEqualTo(GONE);
            assertThat(unit.projects()).containsExactlyInAnyOrder(parent, module);
        });
    }

    @Test
    void doesNotClaimRepositoryGoneIfItCanNotBeTold() {
        // Given
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "selected-org", false, List.of(repo("selected-org", "some", false)))
            .addInstallation("github.com", "org", true, List.of(repo("org", "some", false)))
            .build();
        var ofSelectedInstallation = project("p1", "gone-1", "selected-org", "gone-1");
        var ofUnknownAccount = project("p2", "gone-2", "stranger", "gone-2");
        var referencingTwoRepositories = new DependencyTrackProject("p3", "gone-3", "main", true, Optional.empty(),
            List.of(HousekeepingTestData.gitUrl("org", "a"), HousekeepingTestData.gitUrl("org", "b")), Optional.empty());

        // When
        var units = UnitMatcher.match(RUN, index, List.of(ofSelectedInstallation, ofUnknownAccount, referencingTwoRepositories), Set.of());

        // Then
        var orphans = units.stream().filter(unit -> unit.repository().isEmpty()).toList();
        assertThat(orphans).hasSize(3).allSatisfy(unit -> assertThat(unit.repositoryState()).isEqualTo(UNKNOWN));
    }

    @Test
    void doesNotClaimRepositoryGoneIfItExistsUnderADifferentName() {
        // Given
        var index = index("org", true, repo("org", "renamed", false));
        var project = project("p", "old-name", "org", "renamed");

        // When
        var units = UnitMatcher.match(RUN, index, List.of(project), Set.of());

        // Then
        var orphans = units.stream().filter(unit -> unit.repository().isEmpty()).toList();
        assertThat(orphans).singleElement().satisfies(unit -> {
            assertThat(unit.repositoryState()).isEqualTo(UNKNOWN);
            assertThat(unit.projects()).containsExactly(project);
        });
    }

    @Test
    void handsProjectsWithoutReferencesToTheWorkerAsUnknown() {
        var index = index("org", true, repo("org", "other", false));
        var legacy1 = plainProject("p1", "legacy-1");
        var legacy2 = plainProject("p2", "legacy-2");

        var units = UnitMatcher.match(RUN, index, List.of(legacy1, legacy2), Set.of());

        assertThat(units.stream().filter(unit -> unit.repository().isEmpty()).toList())
            .hasSize(2)
            .allSatisfy(unit -> assertThat(unit.repositoryState()).isEqualTo(UNKNOWN));
    }

    @Test
    void givesProjectsWithSameNameInDifferentAccountsToTheirOwnRepository() {
        // Given
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org-a", true, List.of(repo("org-a", "api", false)))
            .addInstallation("github.com", "org-b", true, List.of(repo("org-b", "api", true)))
            .build();
        var ofA = project("a", "api", "org-a", "api");
        var ofB = project("b", "api", "org-b", "api");

        // When
        var units = UnitMatcher.match(RUN, index, List.of(ofA, ofB), Set.of());

        // Then
        assertThat(units).hasSize(2);
        assertThat(units.get(0).repository()).map(GitHubRepo::fullName).contains("org-a/api");
        assertThat(units.get(0).projects()).containsExactly(ofA);
        assertThat(units.get(1).repository()).map(GitHubRepo::fullName).contains("org-b/api");
        assertThat(units.get(1).projects()).containsExactly(ofB);
    }

    @Test
    void doesNotGiveProjectClaimedByMultipleRepositoriesToAnyOfThem() {
        // Given: no references, so it's unclear which one of the repositories the project belongs to
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org-a", true, List.of(repo("org-a", "api", false)))
            .addInstallation("github.com", "org-b", true, List.of(repo("org-b", "api", true)))
            .build();
        var ambiguous = plainProject("p", "api");

        // When
        var units = UnitMatcher.match(RUN, index, List.of(ambiguous), Set.of());

        // Then
        assertThat(units).hasSize(3);
        assertThat(units.stream().filter(unit -> unit.repository().isPresent())).allSatisfy(unit -> assertThat(unit.projects()).isEmpty());
        assertThat(units.stream().filter(unit -> unit.repository().isEmpty())).singleElement().satisfies(unit -> {
            assertThat(unit.projects()).containsExactly(ambiguous);
            assertThat(unit.repositoryState()).isEqualTo(UNKNOWN);
        });
    }

    @Test
    void keepsRepositoriesWithSharedNamePrefixApart() {
        // Given
        var index = index("org", true, repo("org", "app", false), repo("org", "app-tools", true));
        var ofApp = project("p1", "app", "org", "app");
        var ofTools = project("p2", "app-tools", "org", "app-tools");

        // When
        var units = UnitMatcher.match(RUN, index, List.of(ofApp, ofTools), Set.of());

        // Then
        assertThat(units).hasSize(2);
        assertThat(units.get(0).projects()).containsExactly(ofApp);
        assertThat(units.get(1).projects()).containsExactly(ofTools);
    }

    @Test
    void leavesRepositoriesAndProjectsExcludedByFilterAlone() {
        // Given
        var index = index("org", true, repo("org", "enabled", false), repo("org", "excluded", true));
        var projects = List.of(
            project("p1", "enabled", "org", "enabled"),
            project("p2", "excluded", "org", "excluded"),
            project("p3", "excluded-gone", "org", "excluded-gone"),
            project("p4", "enabled-gone", "org", "Enabled-Gone"),
            plainProject("p5", "legacy")
        );

        // When
        var units = UnitMatcher.match(RUN, index, projects, Set.of("enabled", "enabled-gone"));

        // Then
        assertThat(units).hasSize(2);
        assertThat(units.get(0).repository()).map(GitHubRepo::fullName).contains("org/enabled");
        assertThat(units.get(1).repository()).isEmpty();
        assertThat(units.get(1).projects()).extracting(DependencyTrackProject::uuid).containsExactly("p4");
        assertThat(units.get(1).repositoryState()).isEqualTo(GONE);
    }

    @Test
    void keepsDescendantsWithTheirAncestorsRepository() {
        // Given: children Technolinator wouldn't match by name, like ones created by other means
        var index = index("org", true, repo("org", "app", true));
        var parent = project("parent", "app", "org", "app");
        var differentlyNamed = child("module", "service", "parent", "org", "app");
        var withoutReferences = new DependencyTrackProject("sub", "whatever", "main", true, Optional.of("module"), List.of(), Optional.empty());
        var referencingElsewhere = child("elsewhere", "lib", "parent", "stranger", "lib");

        // When
        var units = UnitMatcher.match(RUN, index, List.of(parent, differentlyNamed, withoutReferences, referencingElsewhere), Set.of());

        // Then
        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.repository()).map(GitHubRepo::fullName).contains("org/app");
            assertThat(unit.projects()).containsExactlyInAnyOrder(parent, differentlyNamed, withoutReferences, referencingElsewhere);
        });
    }

    @Test
    void leavesDescendantClaimedByAnotherRepositoryWithIt() {
        // Given
        var index = index("org", true, repo("org", "app", true), repo("org", "lib", false));
        var parent = project("parent", "app", "org", "app");
        var ofLib = child("lib", "lib", "parent", "org", "lib");
        var belowLib = new DependencyTrackProject("below", "other", "main", true, Optional.of("lib"), List.of(), Optional.empty());

        // When
        var units = UnitMatcher.match(RUN, index, List.of(parent, ofLib, belowLib), Set.of());

        // Then
        assertThat(units).hasSize(2);
        assertThat(units.get(0).repository()).map(GitHubRepo::fullName).contains("org/app");
        assertThat(units.get(0).projects()).containsExactly(parent);
        assertThat(units.get(1).repository()).map(GitHubRepo::fullName).contains("org/lib");
        assertThat(units.get(1).projects()).containsExactlyInAnyOrder(ofLib, belowLib);
    }

    @Test
    void treatsDescendantsOfContestedProjectAsContested() {
        // Given
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org-a", true, List.of(repo("org-a", "api", true)))
            .addInstallation("github.com", "org-b", true, List.of(repo("org-b", "api", true)))
            .build();
        var ambiguous = plainProject("p", "api");
        var module = new DependencyTrackProject("m", "module", "main", true, Optional.of("p"), List.of(), Optional.empty());

        // When
        var units = UnitMatcher.match(RUN, index, List.of(ambiguous, module), Set.of());

        // Then
        assertThat(units.stream().filter(unit -> unit.repository().isPresent())).allSatisfy(unit -> assertThat(unit.projects()).isEmpty());
        assertThat(units.stream().filter(unit -> unit.repository().isEmpty()))
            .hasSize(2)
            .allSatisfy(unit -> assertThat(unit.repositoryState()).isEqualTo(UNKNOWN));
    }

    @Test
    void keepsDescendantsWithTheirAncestorsGoneRepository() {
        // Given
        var index = index("org", true, repo("org", "other", false));
        var parent = project("parent", "deleted", "org", "deleted");
        var withoutReferences = new DependencyTrackProject("module", "module", "main", true, Optional.of("parent"), List.of(), Optional.empty());

        // When
        var units = UnitMatcher.match(RUN, index, List.of(parent, withoutReferences), Set.of());

        // Then
        assertThat(units.stream().filter(unit -> unit.repository().isEmpty())).singleElement().satisfies(unit -> {
            assertThat(unit.repositoryState()).isEqualTo(GONE);
            assertThat(unit.projects()).containsExactlyInAnyOrder(parent, withoutReferences);
        });
    }

    @Test
    void survivesCyclicHierarchy() {
        var index = index("org", true, repo("org", "other", false));
        var a = new DependencyTrackProject("a", "a", "main", true, Optional.of("b"), List.of(), Optional.empty());
        var b = new DependencyTrackProject("b", "b", "main", true, Optional.of("a"), List.of(), Optional.empty());

        var units = UnitMatcher.match(RUN, index, List.of(a, b), Set.of());

        assertThat(units.stream().flatMap(unit -> unit.projects().stream())).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void handsEveryRepositoryAndEveryProjectToExactlyOneUnit() {
        // Given
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org-a", true, List.of(repo("org-a", "app", false), repo("org-a", "lib", true), repo("org-a", "new", false)))
            .addInstallation("github.com", "org-b", false, List.of(repo("org-b", "app", false)))
            .build();
        var projects = new ArrayList<DependencyTrackProject>(List.of(
            project("1", "app", "org-a", "app"),
            child("2", "app-web", "1", "org-a", "app"),
            inactive(project("3", "app", "org-a", "app")),
            project("4", "lib", "org-a", "lib"),
            project("5", "app", "org-b", "app"),
            project("6", "deleted", "org-a", "deleted"),
            project("7", "unknown", "stranger", "unknown"),
            plainProject("8", "legacy"),
            plainProject("9", "app"),
            new DependencyTrackProject("10", "differently-named", "main", true, Optional.of("2"), List.of(), Optional.empty()),
            new DependencyTrackProject("11", "below-deleted", "main", true, Optional.of("6"), List.of(), Optional.empty())
        ));

        // When
        var units = UnitMatcher.match(RUN, index, projects, Set.of());

        // Then
        var handedProjects = units.stream().flatMap(unit -> unit.projects().stream()).map(DependencyTrackProject::uuid).toList();
        assertThat(handedProjects).containsExactlyInAnyOrderElementsOf(projects.stream().map(DependencyTrackProject::uuid).toList());
        var handedRepositories = units.stream().flatMap(unit -> unit.repository().stream()).map(GitHubRepo::fullName).toList();
        assertThat(handedRepositories).containsExactlyInAnyOrder("org-a/app", "org-a/lib", "org-a/new", "org-b/app");
    }

    private static GitHubRepositoryIndex index(String owner, boolean allGranted, GitHubRepo... repositories) {
        return GitHubRepositoryIndex.builder().addInstallation("github.com", owner, allGranted, List.of(repositories)).build();
    }
}
