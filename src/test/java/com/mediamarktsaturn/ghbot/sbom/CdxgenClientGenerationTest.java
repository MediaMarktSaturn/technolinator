package com.mediamarktsaturn.ghbot.sbom;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Optional;

import javax.inject.Inject;

import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class CdxgenClientGenerationTest {

    @Inject
    CdxgenClient cut;

    @Test
    public void testMavenProject() {
        // Given
        var file = new File("src/test/resources/repo/maven");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.group()).isEqualTo("io.github.heubeck");
            assertThat(proper.name()).isEqualTo("examiner");
            assertThat(proper.version()).isEqualTo("1.8.3");
        });
    }

    @Test
    public void testMavenFallbackProject() {
        // Given
        var file = new File("src/test/resources/repo/maven_fallback");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.sbom().getMetadata().getComponent()).isNotNull();
            assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("cdxgen-is-awesome");
            assertThat(proper.sbom().getMetadata().getComponent().getGroup()).isEqualTo("cdxgen-test");
            assertThat(proper.sbom().getComponents()).isNotEmpty();
        });
    }

    @Test
    public void testRecurseMixedProject() {
        // Given
        var file = new File("src/test/resources/repo/multi-mode");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.group()).isEqualTo("io.github.heubeck");
            assertThat(proper.name()).isEqualTo("examiner");
            assertThat(proper.version()).isEqualTo("1.8.3");

            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("husky", "quarkus-smallrye-health");
        });
    }

    @Test
    public void testNodeProject() {
        // Given
        var file = new File("src/test/resources/repo/node");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
            assertThat(fallback.sbom().getComponents()).flatExtracting(Component::getName).containsOnly("husky");
        });
    }

    @Test
    public void testNoopProject() {
        // Given
        var file = new File("src/test/resources/repo/noop");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOf(CdxgenClient.SBOMGenerationResult.None.class);
    }

    @Test
    public void testInvalidGoProject() {
        // Given
        var file = new File("src/test/resources/repo/go");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Invalid.class, invalid -> {
            // there are two license issues in this go.sum
            assertThat(invalid.validationIssues()).hasSize(2);
        });
    }

    @Test
    public void testMultiModuleMavenNodeProject() {
        // Given
        var file = new File("src/test/resources/repo/multi-module-mode");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.group()).isEqualTo("com.mediamarktsaturn.promotion");
            assertThat(proper.name()).isEqualTo("promotion-bos");
            assertThat(proper.version()).isEqualTo("3.2.7");

            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("remapping", "gson");
        });
    }
}
