package com.mediamarktsaturn.technolinator.housekeeping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ACTIVE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ARCHIVED;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.UNKNOWN;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubRepositoryIndexTest {

    @Test
    void reportsStatesOfRepositoriesOfInstallationGrantingAllRepositories() {
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "Org", true, List.of(repo("Org", "Active", false), repo("Org", "old", true)))
            .build();

        assertThat(index.lookup(ref("github.com", "org", "active"))).isEqualTo(ACTIVE);
        assertThat(index.lookup(ref("github.com", "org", "old"))).isEqualTo(ARCHIVED);
        assertThat(index.lookup(ref("github.com", "org", "deleted"))).isEqualTo(GONE);
        assertThat(index.owners()).isEqualTo(1);
    }

    @Test
    void doesNotClaimRepositoriesToBeGoneIfInstallationOnlyGrantsSelectedRepositories() {
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org", false, List.of(repo("org", "active", false), repo("org", "old", true)))
            .build();

        assertThat(index.lookup(ref("github.com", "org", "active"))).isEqualTo(ACTIVE);
        assertThat(index.lookup(ref("github.com", "org", "old"))).isEqualTo(ARCHIVED);
        assertThat(index.lookup(ref("github.com", "org", "not-selected"))).isEqualTo(UNKNOWN);
    }

    @Test
    void doesNotTrustInstallationListingNothing() {
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org", true, List.of())
            .build();

        assertThat(index.lookup(ref("github.com", "org", "anything"))).isEqualTo(UNKNOWN);
    }

    @Test
    void knowsNothingAboutAccountsWithoutInstallation() {
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org", true, List.of(repo("org", "repo", false)))
            .build();

        assertThat(index.lookup(ref("github.com", "someone-else", "repo"))).isEqualTo(UNKNOWN);
        assertThat(index.lookup(ref("ghe.example.com", "org", "repo"))).isEqualTo(UNKNOWN);
    }

    @Test
    void listsRepositoriesOfAllInstallationsOrderedByName() {
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org-b", true, List.of(repo("org-b", "z", false)))
            .addInstallation("github.com", "org-a", false, List.of(repo("org-a", "y", false), repo("org-a", "x", true)))
            .installationFailed()
            .build();

        assertThat(index.repositories()).extracting(GitHubRepo::fullName).containsExactly("org-a/x", "org-a/y", "org-b/z");
        assertThat(index.failedInstallations()).isEqualTo(1);
    }

    private static RepositoryReference ref(String host, String owner, String name) {
        return new RepositoryReference(host, owner, name);
    }
}
