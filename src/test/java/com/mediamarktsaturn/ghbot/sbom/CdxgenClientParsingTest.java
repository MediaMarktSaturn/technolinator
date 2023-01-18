package com.mediamarktsaturn.ghbot.sbom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class CdxgenClientParsingTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "src/test/resources/sbom/empty.json",
        "src/test/resources/sbom/unkown.json"
    })
    public void testInvalids(String filename) {
        // Given
        var file = new File(filename);

        // when
        var result = CdxgenClient.readAndParseSBOM(file);

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Invalid.class, invalid -> {
           assertThat(invalid.validationIssues()).isNotEmpty();
        });
    }

    @Test
    public void testFailure() {
        // Given
        var file = new File("src/test/resources/sbom/invalid.json");

        // when
        var result = CdxgenClient.readAndParseSBOM(file);

        // Then
        assertThat(result).isInstanceOf(CdxgenClient.SBOMGenerationResult.Failure.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "src/test/resources/sbom/does-not-exist.json",
        "src/test/resources/sbom/noop.json"
    })
    public void testNone(String filename) {
        // Given
        var file = new File(filename);

        // when
        var result = CdxgenClient.readAndParseSBOM(file);

        // Then
        assertThat(result).isInstanceOf(CdxgenClient.SBOMGenerationResult.None.class);
    }

    @Test
    public void testFallbackMavenSBOM() {
        // Given
        var file = new File("src/test/resources/sbom/maven/fallback.json");

        // When
        var result = CdxgenClient.readAndParseSBOM(file);

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
            var sbom = fallback.sbom();
            assertThat(sbom.getBomFormat()).isEqualTo("CycloneDX");
            assertThat(sbom.getMetadata().getComponent()).isNull();
        });
    }

    @Test
    public void testDefaultMavenSBOM() {
        // Given
        var file = new File("src/test/resources/sbom/maven/default.json");

        // When
        var result = CdxgenClient.readAndParseSBOM(file);

        // Then
        assertThat(result).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
            var sbom = proper.sbom();
            assertThat(sbom.getBomFormat()).isEqualTo("CycloneDX");
            var metadataComponent = sbom.getMetadata().getComponent();
            assertThat(metadataComponent.getGroup()).isEqualTo("com.mediamarktsaturn.reco");
            assertThat(metadataComponent.getName()).isEqualTo("recommendation-prudsys-emulator");
            assertThat(metadataComponent.getVersion()).isEqualTo("1.6.2");

            assertThat(proper.group()).isEqualTo("com.mediamarktsaturn.reco");
            assertThat(proper.name()).isEqualTo("recommendation-prudsys-emulator");
            assertThat(proper.version()).isEqualTo("1.6.2");
        });
    }
}
