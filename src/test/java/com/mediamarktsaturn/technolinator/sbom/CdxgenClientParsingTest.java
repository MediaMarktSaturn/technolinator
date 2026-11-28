package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CdxgenClientParsingTest {

    @Test
    void analysisWithPrepareScriptDoesNotAcceptPartialSbomAfterFailure(@TempDir Path temp) throws Exception {
        Files.copy(Path.of("src/test/resources/sbom/noop.json"), temp.resolve("sbom.json"));
        var cmd = new CdxgenClient.SbomCreationCommand(temp, "example", "cdxgen", Map.of(), List.of(), "",
            new TechnolinatorConfig.PrepareConfig("true"));
        var cause = new ProcessFailedException("cdxgen", 1, List.of("build failed"));
        var failure = new ProcessHandler.ProcessResult.Failure(List.of("build failed"), 1, cause);
        var metadata = new Command.Metadata("local", "local/test", "", Optional.empty());

        assertThat(CdxgenClient.parseSbom(cmd, failure, metadata)).isInstanceOf(Result.Failure.class);
    }

    @Test
    void failureAfterSkippedPreparationSaysSo(@TempDir Path temp) {
        var cmd = new CdxgenClient.SbomCreationCommand(temp, "example", "cdxgen", Map.of(), List.of(), "", null,
            "the code of pull requests from forks isn't trusted to run scripts");
        var cause = new ProcessFailedException("cdxgen", 1, List.of("build failed"));
        var failure = new ProcessHandler.ProcessResult.Failure(List.of("build failed"), 1, cause);
        var metadata = new Command.Metadata("local", "local/test", "", Optional.empty());

        assertThat(CdxgenClient.parseSbom(cmd, failure, metadata)).isInstanceOfSatisfying(Result.Failure.class, result ->
            assertThat(result.cause()).isInstanceOfSatisfying(ProcessFailedException.class, pfe ->
                assertThat(pfe.notes()).singleElement().asString().contains("was not run").contains("forks")));
    }

    @Test
    void symbolicLinkAsSbomIsRejected(@TempDir Path temp) throws Exception {
        var target = Files.copy(Path.of("src/test/resources/sbom/noop.json"), temp.resolve("target.json"));
        var link = Files.createSymbolicLink(temp.resolve("sbom.json"), target);

        assertThat(CdxgenClient.parseSbomFile(link, "example")).isInstanceOf(Result.Failure.class);
    }

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
