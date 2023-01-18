package com.mediamarktsaturn.ghbot.sbom;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Optional;

import javax.inject.Inject;

import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
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
    public void testMavenWrapperProject() {
        // Given
        var file = new File("src/test/resources/repo/maven_wrapper");

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
        var config = new TechnolinatorConfig(true, null, new TechnolinatorConfig.AnalysisConfig(null, true));

        // When
        var result = await(cut.generateSBOM(file, Optional.of(config)));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, proper -> {
            assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("multi-mode");

            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("husky", "quarkus-smallrye-health");
        });
    }

    @Test
    @Disabled // https://github.com/AppThreat/cdxgen/issues/222
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
    public void testGoProjectWithIssues() {
        // Given
        var file = new File("src/test/resources/repo/go");

        // When
        var result = await(cut.generateSBOM(file, Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
            // there are two license issues in this go.sum
            assertThat(fallback.validationIssues()).hasSize(2);
        });
    }

    @Test
    public void testMultiModuleMavenNodeProject() {
        // Given
        var file = new File("src/test/resources/repo/multi-module-mode");
        var config = new TechnolinatorConfig(true, null, new TechnolinatorConfig.AnalysisConfig(null, true));

        // When
        var result = await(cut.generateSBOM(file, Optional.of(config)));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, proper -> {
            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("remapping", "gson");
        });
    }
}
