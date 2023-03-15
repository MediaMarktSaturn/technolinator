package com.mediamarktsaturn.ghbot.sbom;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.ghbot.ConfigBuilder;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class CdxgenClientGenerationTest {

    @Inject
    CdxgenClient cut;

    @Test
    void testMavenProject() {
        // Given
        var file = new File("src/test/resources/repo/maven");

        // When
        var result = await(cut.generateSBOM(file, "examiner", Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("examiner");
            assertThat(proper.sbom().getMetadata().getComponent().getGroup()).isEqualTo("io.github.heubeck");
            assertThat(proper.sbom().getMetadata().getComponent().getVersion()).isEqualTo("1.8.3");
        });
    }

    @Test
    void testMavenWrapperProject() {
        // Given
        var file = new File("src/test/resources/repo/maven_wrapper");

        // When
        var result = await(cut.generateSBOM(file, "examiner", Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("examiner");
            assertThat(proper.sbom().getMetadata().getComponent().getGroup()).isEqualTo("io.github.heubeck");
            assertThat(proper.sbom().getMetadata().getComponent().getVersion()).isEqualTo("1.8.3");
        });
    }

    @Test
    void testMavenFallbackProject() {
        // Given
        var file = new File("src/test/resources/repo/maven_fallback");

        // When
        var result = await(cut.generateSBOM(file, "cdxgen-is-awesome", Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("cdxgen-is-awesome");
            assertThat(proper.sbom().getMetadata().getComponent().getGroup()).isEqualTo("cdxgen-test");
            assertThat(proper.sbom().getMetadata().getComponent().getVersion()).isEqualTo("1337");
            assertThat(proper.sbom().getComponents()).isNotEmpty();
        });
    }

    @Test
    void testRecurseMixedProject() {
        // Given
        var file = new File("src/test/resources/repo/multi-mode");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, List.of())).enable(true).build();

        // When
        var result = await(cut.generateSBOM(file, "multi-mode", Optional.of(config)));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, proper -> {
            assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("multi-mode");

            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("husky", "quarkus-smallrye-health");
        });
    }

    @Test
    void testNodeProject() {
        // Given
        var file = new File("src/test/resources/repo/node");

        // When
        var result = await(cut.generateSBOM(file, "node", Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
            assertThat(fallback.sbom().getComponents()).flatExtracting(Component::getName).containsOnly("husky");
        });
    }

    @Test
    void testNoopProject() {
        // Given
        var file = new File("src/test/resources/repo/noop");

        // When
        var result = await(cut.generateSBOM(file, "noop", Optional.empty()));

        // Then
        assertThat(result).isInstanceOf(CdxgenClient.SBOMGenerationResult.None.class);
    }

    @Test
    void testGoProjectWithIssues() {
        // Given
        var file = new File("src/test/resources/repo/go");

        // When
        var result = await(cut.generateSBOM(file, "go", Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
            // there are two license issues in this go.sum
            assertThat(fallback.validationIssues()).hasSize(2);
        });
    }

    @Test
    void testMultiModuleMavenNodeProject() {
        // Given
        var file = new File("src/test/resources/repo/multi-module-mode");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, List.of())).enable(true).build();

        // When
        var result = await(cut.generateSBOM(file, "multi-module-mode", Optional.of(config)));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, proper -> {
            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("remapping", "gson");
        });
    }
}
