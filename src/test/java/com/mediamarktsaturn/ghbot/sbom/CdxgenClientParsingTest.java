package com.mediamarktsaturn.ghbot.sbom;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mediamarktsaturn.ghbot.Result;
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
        var file = Paths.get(filename);

        // when
        var result = CdxgenClient.parseSbomFile(file);

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s ->
            assertThat(s.result()).isInstanceOf(CdxgenClient.SBOMGenerationResult.None.class));
    }

    @Test
    void testFailure() {
        // Given
        var file = Paths.get("src/test/resources/sbom/invalid.json");

        // when
        var result = CdxgenClient.parseSbomFile(file);

        // Then
        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "src/test/resources/sbom/does-not-exist.json",
        "src/test/resources/sbom/noop.json"
    })
    public void testNone(String filename) {
        // Given
        var file = Paths.get(filename);

        // when
        var result = CdxgenClient.parseSbomFile(file);

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s ->
            assertThat(s.result()).isInstanceOf(CdxgenClient.SBOMGenerationResult.None.class));
    }

    @Test
    void testFallbackMavenSBOM() {
        // Given
        var file = Paths.get("src/test/resources/sbom/maven/fallback.json");

        // When
        var result = CdxgenClient.parseSbomFile(file);

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
                var sbom = fallback.sbom();
                assertThat(sbom.getBomFormat()).isEqualTo("CycloneDX");
                assertThat(sbom.getMetadata().getComponent()).isNull();
            });
        });
    }

    @Test
    void testDefaultMavenSBOM() {
        // Given
        var file = Paths.get("src/test/resources/sbom/maven/default.json");

        // When
        var result = CdxgenClient.parseSbomFile(file);

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
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
        });
    }
}
