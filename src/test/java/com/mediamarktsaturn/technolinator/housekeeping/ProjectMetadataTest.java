package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.plainProject;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.repo;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.uploadReferences;
import static com.mediamarktsaturn.technolinator.housekeeping.HousekeepingTestData.withMetadata;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectMetadataTest {

    private static final GitHubRepo REPO = repo("MyOrg", "app", false);

    @Test
    void requiresNoUpdateForProjectLikeTheUploadMakesIt() {
        // Given
        var project = withMetadata(plainProject("p", "app"), uploadReferences("MyOrg", "app"), List.of("owner=MyOrg", "java"));

        // When
        var update = ProjectMetadata.requiredUpdate(project, REPO, List.of("java"));

        // Then
        assertThat(update).isEmpty();
    }

    @Test
    void ignoresCaseOfTagsAndTypesOfReferences() {
        // Given
        var references = uploadReferences("MyOrg", "app").stream()
            .map(ref -> new DependencyTrackProject.Reference(ref.type().toUpperCase().replace('-', '_'), ref.url()))
            .toList();
        var project = withMetadata(plainProject("p", "app"), references, List.of("owner=myorg", "JAVA"));

        // When
        var update = ProjectMetadata.requiredUpdate(project, REPO, List.of("java"));

        // Then
        assertThat(update).isEmpty();
    }

    @Test
    void requiresUpdateForChangedTopics() {
        // Given
        var project = withMetadata(plainProject("p", "app"), uploadReferences("MyOrg", "app"), List.of("owner=MyOrg", "java"));

        // When
        var update = ProjectMetadata.requiredUpdate(project, REPO, List.of("java", "quarkus"));

        // Then
        assertThat(update).hasValueSatisfying(it -> assertThat(it.tags()).containsExactly("owner=MyOrg", "java", "quarkus"));
    }

    @Test
    void requiresUpdateForReferenceToOtherUrl() {
        // Given
        var references = List.of(
            new DependencyTrackProject.Reference("vcs", "git://github.com/MyOrg/old.git"),
            new DependencyTrackProject.Reference("website", "https://github.com/MyOrg/old")
        );
        var project = withMetadata(plainProject("p", "app"), references, List.of("owner=MyOrg"));

        // When
        var update = ProjectMetadata.requiredUpdate(project, REPO, List.of());

        // Then
        assertThat(update).hasValueSatisfying(it -> assertThat(it.references()).containsExactlyElementsOf(uploadReferences("MyOrg", "app")));
    }

    @Test
    void detectsMissingMetadataWithoutTopics() {
        var complete = withMetadata(plainProject("p", "app"), uploadReferences("MyOrg", "app"), List.of("owner=MyOrg"));
        var withoutOwner = withMetadata(plainProject("p", "app"), uploadReferences("MyOrg", "app"), List.of("java"));
        var withoutReleaseNotes = withMetadata(plainProject("p", "app"), uploadReferences("MyOrg", "app").subList(0, 2), List.of("owner=MyOrg"));

        assertThat(ProjectMetadata.lacksRepositoryMetadata(complete, REPO)).isFalse();
        assertThat(ProjectMetadata.lacksRepositoryMetadata(withoutOwner, REPO)).isTrue();
        assertThat(ProjectMetadata.lacksRepositoryMetadata(withoutReleaseNotes, REPO)).isTrue();
        assertThat(ProjectMetadata.lacksRepositoryMetadata(plainProject("p", "app"), REPO)).isTrue();
    }
}
