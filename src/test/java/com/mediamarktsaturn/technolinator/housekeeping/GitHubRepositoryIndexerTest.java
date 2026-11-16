package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkiverse.githubapp.GitHubClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHObject;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySelection;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedSearchIterable;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ACTIVE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.ARCHIVED;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.GONE;
import static com.mediamarktsaturn.technolinator.housekeeping.GitHubRepositoryIndex.State.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubRepositoryIndexerTest {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-01-01T02:00:00Z");
    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", NOW, false);

    private GitHubClientProvider clientProvider;
    private GitHub applicationClient;
    private final List<GHAppInstallation> installations = new CopyOnWriteArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        clientProvider = Mockito.mock(GitHubClientProvider.class);
        applicationClient = Mockito.mock(GitHub.class);
        var app = Mockito.mock(GHApp.class);
        PagedIterable<GHAppInstallation> pagedInstallations = Mockito.mock(PagedIterable.class);
        Mockito.when(clientProvider.getApplicationClient()).thenReturn(applicationClient);
        Mockito.when(applicationClient.getApp()).thenReturn(app);
        Mockito.when(app.listInstallations()).thenReturn(pagedInstallations);
        Mockito.when(pagedInstallations.toList()).thenAnswer(invocation -> List.copyOf(installations));
    }

    @Test
    void indexesRepositoriesOfAllActiveInstallations() throws Exception {
        // Given
        installation(1, "org-a", GHRepositorySelection.ALL, false, repository("active", false), repository("old", true));
        installation(2, "org-b", GHRepositorySelection.SELECTED, false, repository("selected", false));
        installation(3, "org-c", GHRepositorySelection.ALL, true, repository("suspended", false));

        // When
        var index = indexer().build(RUN).await().atMost(AWAIT);

        // Then
        assertThat(index.owners()).isEqualTo(2);
        assertThat(index.failedInstallations()).isZero();
        assertThat(index.repositories()).extracting(GitHubRepo::fullName).containsExactly("org-a/active", "org-a/old", "org-b/selected");
        var active = index.repositories().getFirst();
        assertThat(active.htmlUrl()).isEqualTo("https://github.com/org-a/active");
        assertThat(active.gitUrl()).isEqualTo("git://github.com/org-a/active.git");
        assertThat(active.installation().installationId()).isEqualTo(1L);
        assertThat(index.repositories().get(1).archived()).isTrue();
        assertThat(index.lookup(ref("org-a", "active"))).isEqualTo(ACTIVE);
        assertThat(index.lookup(ref("org-a", "old"))).isEqualTo(ARCHIVED);
        assertThat(index.lookup(ref("org-a", "deleted"))).isEqualTo(GONE);
        assertThat(index.lookup(ref("org-b", "selected"))).isEqualTo(ACTIVE);
        assertThat(index.lookup(ref("org-b", "other"))).isEqualTo(UNKNOWN);
        assertThat(index.lookup(ref("org-c", "suspended"))).isEqualTo(UNKNOWN);
        Mockito.verify(clientProvider, Mockito.never()).getInstallationClient(3L);
    }

    @Test
    void leavesOutInstallationsThatCanNotBeListed() throws Exception {
        // Given
        installation(1, "org-a", GHRepositorySelection.ALL, false, repository("fine", false));
        installations.add(installationObject(2, "org-broken", GHRepositorySelection.ALL, false));
        Mockito.when(clientProvider.getInstallationClient(2L)).thenThrow(new IllegalStateException("token creation failed"));

        // When
        var index = indexer().build(RUN).await().atMost(AWAIT);

        // Then
        assertThat(index.lookup(ref("org-a", "fine"))).isEqualTo(ACTIVE);
        assertThat(index.lookup(ref("org-a", "deleted"))).isEqualTo(GONE);
        assertThat(index.lookup(ref("org-broken", "anything"))).isEqualTo(UNKNOWN);
        assertThat(index.failedInstallations()).isEqualTo(1);
        assertThat(index.repositories()).extracting(GitHubRepo::fullName).containsExactly("org-a/fine");
    }

    @Test
    void failsIfInstallationsCanNotBeListed() throws Exception {
        // Given
        Mockito.when(applicationClient.getApp()).thenThrow(new IOException("bad credentials"));

        // When, Then
        assertThatThrownBy(() -> indexer().build(RUN).await().atMost(AWAIT)).hasRootCauseMessage("bad credentials");
    }

    private GitHubRepositoryIndexer indexer() {
        return new GitHubRepositoryIndexer(clientProvider, 2, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RepositoryReference ref(String owner, String name) {
        return new RepositoryReference("github.com", owner, name);
    }

    private void installation(long id, String account, GHRepositorySelection selection, boolean suspended, GHRepository... repositories) throws Exception {
        installations.add(installationObject(id, account, selection, suspended));

        var client = Mockito.mock(GitHub.class);
        var authenticated = Mockito.mock(GHAuthenticatedAppInstallation.class);
        @SuppressWarnings("unchecked")
        PagedSearchIterable<GHRepository> paged = Mockito.mock(PagedSearchIterable.class);
        Mockito.when(paged.toList()).thenReturn(new ArrayList<>(List.of(repositories)));
        Mockito.when(authenticated.listRepositories()).thenReturn(paged);
        Mockito.when(client.getInstallation()).thenReturn(authenticated);
        Mockito.when(clientProvider.getInstallationClient(id)).thenReturn(client);
    }

    /**
     * GHObject#getId can't be mocked or spied by Mockito, because of the deprecated bridge methods differing in the return type only,
     * so a real object with its fields set reflectively is used.
     */
    private static GHAppInstallation installationObject(long id, String account, GHRepositorySelection selection, boolean suspended) throws Exception {
        var installation = new GHAppInstallation();
        var idField = GHObject.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setLong(installation, id);

        var htmlUrlField = GHAppInstallation.class.getDeclaredField("htmlUrl");
        htmlUrlField.setAccessible(true);
        htmlUrlField.set(installation, "https://github.com/organizations/" + account + "/settings/installations/" + id);

        var user = Mockito.mock(GHUser.class);
        Mockito.when(user.getLogin()).thenReturn(account);
        var accountField = GHAppInstallation.class.getDeclaredField("account");
        accountField.setAccessible(true);
        accountField.set(installation, user);
        var selectionField = GHAppInstallation.class.getDeclaredField("repositorySelection");
        selectionField.setAccessible(true);
        selectionField.set(installation, selection);

        if (suspended) {
            var suspendedAtField = GHAppInstallation.class.getDeclaredField("suspendedAt");
            suspendedAtField.setAccessible(true);
            suspendedAtField.set(installation, "2024-01-01T00:00:00Z");
        }
        return installation;
    }

    private static GHRepository repository(String name, boolean archived) {
        var repository = Mockito.mock(GHRepository.class);
        Mockito.when(repository.getName()).thenReturn(name);
        Mockito.when(repository.isArchived()).thenReturn(archived);
        return repository;
    }
}
