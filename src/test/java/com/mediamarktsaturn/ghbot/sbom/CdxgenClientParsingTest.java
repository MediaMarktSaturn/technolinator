package com.mediamarktsaturn.ghbot.sbom;

import java.io.File;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class CdxgenClientParsingTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "src/test/resources/sbom/does-not-exist.json",
        "src/test/resources/sbom/empty.json",
        "src/test/resources/sbom/invalid.json",
        "src/test/resources/sbom/unkown.json"
    })
    public void testFailures(String filename) {
        // Given
        var file = new File(filename);

        // when
        var result = CdxgenClient.readAndParseSBOM(file);

        // Then
        assertThat(result).isInstanceOf(CdxgenClient.SBOMGenerationResult.Failure.class);
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
