package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
}
