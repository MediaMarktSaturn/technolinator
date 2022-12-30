package com.mediamarktsaturn.ghbot.sbom;

import java.io.File;

import javax.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class CdxgenClientGenerationTest {

    @Inject
    CdxgenClient cut;

    @Test
    public void testMavenProject() {
        // Given
        var file = new File("src/test/resources/repo/maven");

        // When
        var result = await(cut.generateSBOM(file));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.group()).isEqualTo("io.github.heubeck");
            assertThat(proper.name()).isEqualTo("examiner");
            assertThat(proper.version()).isEqualTo("1.8.3");
        });
    }

    @Test
    public void testRecurseMixedProject() {
        // Given
        var file = new File("src/test/resources/repo/multi-mode");

        // When
        var result = await(cut.generateSBOM(file));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.group()).isEqualTo("io.github.heubeck");
            assertThat(proper.name()).isEqualTo("examiner");
            assertThat(proper.version()).isEqualTo("1.8.3");

            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("husky");
        });


    }

    @Test
    public void testNodeProject() {
        // Given
        var file = new File("src/test/resources/repo/node");

        // When
        var result = await(cut.generateSBOM(file));

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).containsOnly("husky");
        });

    }
}
