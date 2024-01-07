package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CdxgenClientParsingTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "src/test/resources/sbom/empty.json",
        "src/test/resources/sbom/unkown.json",
        "src/test/resources/sbom/does-not-exist.json",
    })
    void testInvalids(String filename) {
        // Given
        var file = Paths.get(filename);

        // when
        var result = CdxgenClient.parseSbomFile(file, "myProject");

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s ->
            assertThat(s.result()).isInstanceOf(CdxgenClient.SBOMGenerationResult.None.class));
    }

    @Test
    void testFailure() {
        // Given
        var file = Paths.get("src/test/resources/sbom/invalid.json");

        // when
        var result = CdxgenClient.parseSbomFile(file, "myProject");

        // Then
        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void testMinimal() {
        // Given
        var file = Paths.get("src/test/resources/sbom/noop.json");

        // when
        var result = CdxgenClient.parseSbomFile(file, "myProject");

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s ->
            assertThat(s.result()).isInstanceOf(CdxgenClient.SBOMGenerationResult.Yield.class));
    }

    @Test
    void testFallbackMavenSBOM() {
        // Given
        var file = Paths.get("src/test/resources/sbom/maven/fallback.json");

        // When
        var result = CdxgenClient.parseSbomFile(file, "myProject");

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                assertThat(yield.projectName()).hasToString("myProject");
                var sbom = yield.sbom();
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
        var result = CdxgenClient.parseSbomFile(file, "myProject");

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                assertThat(yield.projectName()).hasToString("myProject");
                var sbom = yield.sbom();
                assertThat(sbom.getBomFormat()).hasToString("CycloneDX");
                var metadataComponent = sbom.getMetadata().getComponent();
                assertThat(metadataComponent.getGroup()).hasToString("com.mediamarktsaturn.reco");
                assertThat(metadataComponent.getName()).hasToString("recommendation-prudsys-emulator");
                assertThat(metadataComponent.getVersion()).hasToString("1.6.2");

                assertThat(yield.sbom().getMetadata().getComponent().getGroup()).hasToString("com.mediamarktsaturn.reco");
                assertThat(yield.sbom().getMetadata().getComponent().getName()).hasToString("recommendation-prudsys-emulator");
                assertThat(yield.sbom().getMetadata().getComponent().getVersion()).hasToString("1.6.2");
            });
        });
    }

    @Test
    void testCdx1dot5spec() {
        // Given
        var file = Paths.get("src/test/resources/sbom/technolinator-cdx-1.5.json");

        // When
        var result = CdxgenClient.parseSbomFile(file, "technolinator");

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                assertThat(yield.projectName()).hasToString("technolinator");
                var sbom = yield.sbom();
                assertThat(sbom.getBomFormat()).hasToString("CycloneDX");
                assertThat(sbom.getSpecVersion()).hasToString("1.5");
            });
        });
    }
}
