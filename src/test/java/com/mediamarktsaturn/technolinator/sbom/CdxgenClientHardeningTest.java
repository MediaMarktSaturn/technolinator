package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class CdxgenClientHardeningTest {

    @Inject
    CdxgenClient cut;

    @TempDir
    Path tmp;

    @Test
    void testAnalysisLocationMustNotLeaveRepository() {
        // Given
        var repo = tmp.resolve("repo");
        var config = ConfigBuilder.create()
            .analysis(new TechnolinatorConfig.AnalysisConfig("../../etc", null, null, null, null, List.of()))
            .build();

        // When & Then
        assertThatThrownBy(() -> CdxgenClient.determineAnalysisFolder(repo, List.of(config)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testAnalysisLocationMayStepUpWithinRepository() {
        // Given
        var repo = tmp.resolve("repo");
        var config = ConfigBuilder.create()
            .analysis(new TechnolinatorConfig.AnalysisConfig("a/../b", null, null, null, null, List.of()))
            .build();

        // When
        var location = CdxgenClient.determineAnalysisFolder(repo, List.of(config));

        // Then
        assertThat(location.normalize()).isEqualTo(repo.resolve("b"));
    }

    @Test
    void testProjectNameIsSingleCommandLineArgument() {
        // Given
        var config = ConfigBuilder.create()
            .project(new TechnolinatorConfig.ProjectConfig(" my module --evil "))
            .build();

        // When
        var name = cut.determineProjectName("repo", List.of(config));

        // Then
        assertThat(name).isEqualTo("repo-my-module---evil");
    }

    @Test
    void testUnknownJdkVersionFailsWithMeaningfulMessage() {
        // Given
        var config = ConfigBuilder.create().jdk(new TechnolinatorConfig.JdkConfig("99")).build();

        // When & Then
        assertThatThrownBy(() -> cut.buildEnv(List.of(config), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("99");
    }

    @Test
    void testExcludedFilesAreRemovedWithinRepositoryOnly() throws IOException {
        // Given
        var repo = Files.createDirectory(tmp.resolve("repo"));
        Files.createDirectories(repo.resolve("generated/deep"));
        Files.writeString(repo.resolve("generated/deep/file.txt"), "x");
        Files.writeString(repo.resolve("mvnw"), "x");
        Files.writeString(repo.resolve("keep.txt"), "x");
        var outside = Files.writeString(tmp.resolve("outside.txt"), "keep me");
        var cmd = new CdxgenClient.SbomCreationCommand(repo, "repo", "", Map.of(),
            // none of the nasty ones may have an effect outside of the repository or on the repository itself
            List.of("generated", "mvnw", "../outside.txt", "/etc/hostname", ".", "never-existed", "keep.txt ../outside.txt"));

        // When
        await(CdxgenClient.removeExcludedFiles(cmd));

        // Then
        assertThat(repo.resolve("generated")).doesNotExist();
        assertThat(repo.resolve("mvnw")).doesNotExist();
        assertThat(repo).exists();
        assertThat(repo.resolve("keep.txt")).exists();
        assertThat(outside).hasContent("keep me");
    }
}
