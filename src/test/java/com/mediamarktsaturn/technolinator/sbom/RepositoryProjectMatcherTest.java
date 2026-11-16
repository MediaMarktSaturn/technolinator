package com.mediamarktsaturn.technolinator.sbom;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryProjectMatcherTest {

    private static final String VCS = "git://github.com/org/repo.git";
    private static final String WEBSITE = "https://github.com/org/repo";
    private static final String OTHER_VCS = "git://github.com/other/repo.git";
    private static final String OTHER_WEBSITE = "https://github.com/other/repo";

    @Test
    void selectsProjectsReferencingTheRepositoryInAllVersionsAndOfAllModules() {
        // Given
        var parent = project("parent", "repo", Optional.empty(), VCS, WEBSITE);
        var oldVersion = project("old", "repo", Optional.empty(), VCS, WEBSITE);
        var module = project("module", "repo-api", Optional.of("parent"), VCS, WEBSITE);

        // When
        var selected = RepositoryProjectMatcher.select(List.of(parent, oldVersion, module), "repo", VCS, WEBSITE);

        // Then
        assertThat(selected).containsExactlyInAnyOrder(parent, oldVersion, module);
    }

    @Test
    void ignoresReferenceCase() {
        var project = project("p", "repo", Optional.empty(), "GIT://GitHub.com/Org/Repo.git", "https://github.com/ORG/REPO");

        assertThat(RepositoryProjectMatcher.select(List.of(project), "repo", VCS, WEBSITE)).containsExactly(project);
    }

    @Test
    void neverSelectsProjectsReferencingAnotherRepository() {
        // Given
        var sameNameOtherOwner = project("p1", "repo", Optional.empty(), OTHER_VCS, OTHER_WEBSITE);
        var moduleOfOtherOwner = project("p2", "repo-api", Optional.empty(), OTHER_VCS, OTHER_WEBSITE);

        // When
        var selected = RepositoryProjectMatcher.select(List.of(sameNameOtherOwner, moduleOfOtherOwner), "repo", VCS, WEBSITE);

        // Then
        assertThat(selected).isEmpty();
    }

    @Test
    void doesNotMixUpRepositoriesWithSharedNamePrefix() {
        // Given
        var ofSibling = project("p", "repo-sibling", Optional.empty(), "git://github.com/org/repo-sibling.git", "https://github.com/org/repo-sibling");

        // When, Then
        assertThat(RepositoryProjectMatcher.select(List.of(ofSibling), "repo", VCS, WEBSITE)).isEmpty();
        assertThat(RepositoryProjectMatcher.select(List.of(ofSibling), "repo-sibling", "git://github.com/org/repo-sibling.git", "https://github.com/org/repo-sibling"))
            .containsExactly(ofSibling);
    }

    @Test
    void doesNotSelectProjectsOfOtherNames() {
        var unrelated = project("p", "repository", Optional.empty(), VCS, WEBSITE);

        assertThat(RepositoryProjectMatcher.select(List.of(unrelated), "repo", VCS, WEBSITE)).isEmpty();
    }

    @Test
    void selectsProjectsWithoutReferencesByExactNameOnly() {
        // Given
        var exact = project("exact", "repo", Optional.empty());
        var prefixOnly = project("prefix", "repo-api", Optional.empty());

        // When
        var selected = RepositoryProjectMatcher.select(List.of(exact, prefixOnly), "repo", VCS, WEBSITE);

        // Then
        assertThat(selected).containsExactly(exact);
    }

    @Test
    void adoptsNestedChildrenWithoutReferencesOfMatchedProjects() {
        // Given
        var parent = project("parent", "repo", Optional.empty(), VCS, WEBSITE);
        var child = project("child", "repo-api", Optional.of("parent"));
        var grandChild = project("grand-child", "repo-api-impl", Optional.of("child"));
        var orphan = project("orphan", "repo-other", Optional.empty());

        // When
        var selected = RepositoryProjectMatcher.select(List.of(grandChild, child, orphan, parent), "repo", VCS, WEBSITE);

        // Then
        assertThat(selected).containsExactlyInAnyOrder(parent, child, grandChild);
    }

    @Test
    void listsRepositoryNamesAProjectCouldBelongTo() {
        assertThat(RepositoryProjectMatcher.possibleRepoNames("my-repo-api")).containsExactly("my-repo-api", "my", "my-repo");
        assertThat(RepositoryProjectMatcher.possibleRepoNames("repo")).containsExactly("repo");
        assertThat(RepositoryProjectMatcher.possibleRepoNames("-odd")).containsExactly("-odd");
        assertThat(RepositoryProjectMatcher.possibleRepoNames("")).isEmpty();
        assertThat(RepositoryProjectMatcher.possibleRepoNames(null)).isEmpty();
    }

    @Test
    void possibleRepoNamesAreConsistentWithNameMatching() {
        for (var projectName : List.of("repo", "repo-api", "my-repo-api", "a--b", "repo-")) {
            for (var repoName : List.of("repo", "my", "my-repo", "a", "a-", "other", "repo-api")) {
                assertThat(RepositoryProjectMatcher.possibleRepoNames(projectName).contains(repoName))
                    .as("project %s, repo %s", projectName, repoName)
                    .isEqualTo(RepositoryProjectMatcher.matchesRepoName(projectName, repoName));
            }
        }
    }

    private static DependencyTrackProject project(String uuid, String name, Optional<String> parent, String... references) {
        return new DependencyTrackProject(uuid, name, "main", true, parent, List.of(references), Optional.empty());
    }
}
