package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.child;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.project;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetedListingTest {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final HousekeepingRun RUN = new HousekeepingRun("run-1", Instant.parse("2026-01-01T02:00:00Z"), false);
    private static final String UUID = "0b8a3f1e-1111-2222-3333-444455556666";

    private GitHubRepositoryIndexer indexer;
    private DependencyTrackClient dtrack;
    private TargetedListing cut;

    @BeforeEach
    void setUp() {
        indexer = mock(GitHubRepositoryIndexer.class);
        dtrack = mock(DependencyTrackClient.class);
        cut = new TargetedListing(indexer, dtrack);
        when(dtrack.searchProjects(anyString())).thenReturn(Uni.createFrom().item(List.of()));
        when(dtrack.listChildren(anyString())).thenReturn(Uni.createFrom().item(List.of()));
        when(dtrack.getProject(anyString())).thenReturn(Uni.createFrom().item(Optional.empty()));
    }

    private void givenIndex(GitHubRepo... repos) {
        when(indexer.build(any(), any())).thenReturn(Uni.createFrom().item(
            GitHubRepositoryIndex.builder().addInstallation("github.com", "org", true, List.of(repos)).build()));
    }

    private void givenSearch(String text, DependencyTrackProject... projects) {
        when(dtrack.searchProjects(text)).thenReturn(Uni.createFrom().item(List.of(projects)));
    }

    private void givenChildren(String uuid, DependencyTrackProject... projects) {
        when(dtrack.listChildren(uuid)).thenReturn(Uni.createFrom().item(List.of(projects)));
    }

    private TargetedListing.Listing load(Set<String> repositories, Set<String> projects) {
        return cut.load(RUN, new HousekeepingSelection(repositories, projects)).await().atMost(AWAIT);
    }

    @SuppressWarnings("unchecked")
    private Collection<GitHubRepositoryIndexer.Target> requestedTargets() {
        ArgumentCaptor<Collection<GitHubRepositoryIndexer.Target>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(indexer).build(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void findsProjectsOfSelectedRepositoryByRepositoryName() {
        // Given
        givenIndex(repo("org", "app", false));
        givenSearch("app",
            project("p1", "app", "org", "app"),
            project("p2", "app-module", "org", "app"),
            // same name prefix, but another repository
            project("p3", "app-other", "someone", "app-other"),
            plainProject("p4", "application")
        );

        // When
        var listing = load(Set.of("org/app"), Set.of());

        // Then
        assertThat(requestedTargets()).containsExactly(new GitHubRepositoryIndexer.Target("org", "app"));
        assertThat(listing.projects()).extracting(DependencyTrackProject::uuid).containsExactlyInAnyOrder("p1", "p2");
        assertThat(listing.index().repositories()).extracting(GitHubRepo::fullName).containsExactly("org/app");
        verify(dtrack, never()).listProjects(true);
        verify(dtrack, never()).listProjects(false);
    }

    @Test
    void addsHierarchyOfFoundProjects() {
        // Given
        givenIndex(repo("org", "app", false));
        var parent = plainProject("parent", "monorepo");
        var module = child("module", "app", "parent", "org", "app");
        var grandChild = child("grand-child", "differently-named", "module", "org", "app");
        givenSearch("app", module);
        when(dtrack.getProject("parent")).thenReturn(Uni.createFrom().item(Optional.of(parent)));
        givenChildren("module", grandChild);

        // When
        var listing = load(Set.of("org/app"), Set.of());

        // Then
        assertThat(listing.projects()).extracting(DependencyTrackProject::uuid).containsExactlyInAnyOrder("parent", "module", "grand-child");
    }

    @Test
    void findsSelectedProjectsByNameAndUuidAndTheRepositoriesTheyReference() {
        // Given
        givenIndex(repo("org", "app", false));
        var byName = project("p1", "App", "org", "app");
        var byUuid = project(UUID, "other", "org", "other");
        givenSearch("app", byName, project("p2", "app-module", "org", "app"));
        givenSearch("other");
        when(dtrack.getProject(UUID)).thenReturn(Uni.createFrom().item(Optional.of(byUuid)));

        // When
        var listing = load(Set.of(), Set.of("APP", UUID));

        // Then
        assertThat(requestedTargets()).containsExactlyInAnyOrder(
            new GitHubRepositoryIndexer.Target("org", "app"), new GitHubRepositoryIndexer.Target("org", "other"));
        // by name only exact matches are selected, the other one belongs to the repository, though
        assertThat(listing.projects()).extracting(DependencyTrackProject::uuid).containsExactlyInAnyOrder("p1", "p2", UUID);
    }

    @Test
    void findsProjectsOfRepositoriesThatDontExistAnymoreByTheirReference() {
        // Given
        givenIndex(repo("org", "other", false));
        givenSearch("gone", project("p1", "gone", "org", "gone"), project("p2", "gone-module", "someone", "gone"));

        // When
        var listing = load(Set.of("org/gone"), Set.of());

        // Then
        assertThat(listing.projects()).extracting(DependencyTrackProject::uuid).containsExactly("p1");
        assertThat(listing.index().lookup(new RepositoryReference("github.com", "org", "gone"))).isEqualTo(GitHubRepositoryIndex.State.GONE);
    }

    @Test
    void ignoresRepositoriesWithoutOwner() {
        // Given
        givenIndex();

        // When
        var listing = load(Set.of("app"), Set.of());

        // Then
        assertThat(requestedTargets()).isEmpty();
        assertThat(listing.projects()).isEmpty();
    }
}
