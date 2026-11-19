package com.mediamarktsaturn.technolinator.housekeeping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ACTIVE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ARCHIVED;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.MOVED;
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

    @Test
    void reportsSingleRepositoriesLookedAtOnTheirOwn() {
        var index = GitHubRepositoryIndex.builder()
            .addRepository("github.com", "Org", repo("Org", "Active", false))
            .addRepository("github.com", "org", repo("org", "old", true))
            .addGoneRepository("github.com", "org", "Deleted")
            .build();

        assertThat(index.lookup(ref("github.com", "org", "active"))).isEqualTo(ACTIVE);
        assertThat(index.lookup(ref("github.com", "org", "old"))).isEqualTo(ARCHIVED);
        assertThat(index.lookup(ref("github.com", "org", "deleted"))).isEqualTo(GONE);
        // only what was looked at is known
        assertThat(index.lookup(ref("github.com", "org", "other"))).isEqualTo(UNKNOWN);
        assertThat(index.repositories()).extracting(GitHubRepo::fullName).containsExactly("Org/Active", "org/old");
        assertThat(index.owners()).isEqualTo(1);
    }

    @Test
    void reportsRepositoriesFoundToBeRenamed() {
        var target = repo("org", "new-name", false);
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org", true, List.of(target))
            .addMovedRepository("github.com", "Org", "Old-Name", target)
            .build();

        assertThat(index.lookup(ref("github.com", "org", "old-name"))).isEqualTo(MOVED);
        assertThat(index.movedTo(ref("github.com", "org", "old-name"))).contains(target);
        assertThat(index.movedTo(ref("github.com", "org", "new-name"))).isEmpty();
        assertThat(index.lookup(ref("github.com", "org", "new-name"))).isEqualTo(ACTIVE);
        // renamed ones aren't repositories of their own
        assertThat(index.repositories()).extracting(GitHubRepo::fullName).containsExactly("org/new-name");
    }

    @Test
    void addsRenamedRepositoriesLaterOn() {
        var target = repo("org", "new-name", false);
        var index = GitHubRepositoryIndex.builder()
            .addInstallation("github.com", "org", true, List.of(target))
            .build();
        assertThat(index.lookup(ref("github.com", "org", "old-name"))).isEqualTo(GONE);

        var withMoved = index.withMoved(Map.of(ref("github.com", "org", "old-name"), target));

        assertThat(withMoved.lookup(ref("github.com", "org", "old-name"))).isEqualTo(MOVED);
        assertThat(withMoved.withUnconfirmedGone(List.of(ref("github.com", "org", "old-name"))).lookup(ref("github.com", "org", "old-name"))).isEqualTo(MOVED);
    }

    private static RepositoryReference ref(String host, String owner, String name) {
        return new RepositoryReference(host, owner, name);
    }
}
