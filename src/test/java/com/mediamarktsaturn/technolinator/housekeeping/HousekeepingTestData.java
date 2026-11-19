package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Builders for the data housekeeping tests work with
 */
final class HousekeepingTestData {

    private HousekeepingTestData() {
    }

    static GitHubRepo repo(String owner, String name, boolean archived) {
        return new GitHubRepo(
            new RepositoryReference("github.com", owner.toLowerCase(), name.toLowerCase()),
            name,
            owner + "/" + name,
            webUrl(owner, name),
            gitUrl(owner, name),
            archived,
            null,
            null
        );
    }

    static String webUrl(String owner, String name) {
        return "https://github.com/%s/%s".formatted(owner, name);
    }

    static String gitUrl(String owner, String name) {
        return "git://github.com/%s/%s.git".formatted(owner, name);
    }

    /**
     * A project like Technolinator creates it, referencing the repository the same way
     */
    static DependencyTrackProject project(String uuid, String name, String owner, String repoName) {
        return new DependencyTrackProject(uuid, name, "main", true, Optional.empty(),
            List.of(gitUrl(owner, repoName), webUrl(owner, repoName)), Optional.empty());
    }

    static DependencyTrackProject child(String uuid, String name, String parentUuid, String owner, String repoName) {
        return new DependencyTrackProject(uuid, name, "main", true, Optional.of(parentUuid),
            List.of(gitUrl(owner, repoName), webUrl(owner, repoName)), Optional.empty());
    }

    /**
     * A project without any reference to a repository
     */
    static DependencyTrackProject plainProject(String uuid, String name) {
        return new DependencyTrackProject(uuid, name, "main", true, Optional.empty(), List.of(), Optional.empty());
    }

    static DependencyTrackProject inactive(DependencyTrackProject project) {
        return new DependencyTrackProject(project.uuid(), project.name(), project.version(), false, project.parentUuid(), project.repositoryUrls(), project.lastBomImport());
    }

    static DependencyTrackProject uploadedAt(DependencyTrackProject project, Instant lastBomImport) {
        return new DependencyTrackProject(project.uuid(), project.name(), project.version(), project.active(), project.parentUuid(), project.repositoryUrls(), Optional.of(lastBomImport));
    }

    static DependencyTrackProject withMetadata(DependencyTrackProject project, List<DependencyTrackProject.Reference> references, List<String> tags) {
        return new DependencyTrackProject(project.uuid(), project.name(), project.version(), project.active(), project.parentUuid(),
            project.repositoryUrls(), project.lastBomImport(), references, tags);
    }

    /**
     * The references Technolinator puts to the projects of a repository
     */
    static List<DependencyTrackProject.Reference> uploadReferences(String owner, String repoName) {
        return DependencyTrackClient.repositoryReferences(gitUrl(owner, repoName), webUrl(owner, repoName));
    }

    static DependencyTrackProject withVersion(DependencyTrackProject project, String version) {
        return new DependencyTrackProject(project.uuid(), project.name(), version, project.active(), project.parentUuid(),
            project.repositoryUrls(), project.lastBomImport());
    }

    /**
     * Lets the mocked client answer the lookups of the current state of projects ({@code getProject}, {@code searchProjects}) from the returned map
     * (uuid to project), which represents Dependency-Track as it is now. Projects not in the map are deleted.
     * Tests can stub single projects differently afterwards.
     */
    static Map<String, DependencyTrackProject> currentStates(DependencyTrackClient dtrack) {
        var current = new ConcurrentHashMap<String, DependencyTrackProject>();
        when(dtrack.getProject(anyString())).thenAnswer(invocation ->
            Uni.createFrom().item(Optional.ofNullable(current.get(invocation.<String>getArgument(0)))));
        when(dtrack.searchProjects(anyString())).thenAnswer(invocation -> {
            var text = invocation.<String>getArgument(0).toLowerCase(Locale.ROOT);
            return Uni.createFrom().item(current.values().stream().filter(project -> project.name().toLowerCase(Locale.ROOT).contains(text)).toList());
        });
        return current;
    }

    /**
     * Adds the projects of the unit to the [current] states, unless they're known already, so by default the snapshot of the unit is up to date
     */
    static HousekeepingUnit known(Map<String, DependencyTrackProject> current, HousekeepingUnit unit) {
        unit.projects().forEach(project -> current.putIfAbsent(project.uuid(), project));
        return unit;
    }

    static HousekeepingWorker.Settings settings(
        HousekeepingWorker.Feature followRenamedRepositories,
        HousekeepingWorker.Feature archiveOrphanedProjects,
        HousekeepingWorker.Feature archiveRemovedModules,
        HousekeepingWorker.Feature reactivateProjects,
        HousekeepingWorker.Feature updateProjectMetadata
    ) {
        return new HousekeepingWorker.Settings(Duration.ofSeconds(10), followRenamedRepositories, archiveOrphanedProjects,
            archiveRemovedModules, reactivateProjects, updateProjectMetadata);
    }
}
