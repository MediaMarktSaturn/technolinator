package com.mediamarktsaturn.technolinator.sbom;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class CdxgenClientGenerationTest {

    @Inject
    CdxgenClient cut;

    @Test
    void testMavenProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/maven");

        // When
        var result = generateSBOM(file, "examiner", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
                assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("examiner");
                assertThat(proper.sbom().getMetadata().getComponent().getGroup()).isEqualTo("io.github.heubeck");
                assertThat(proper.sbom().getMetadata().getComponent().getVersion()).isEqualTo("1.8.3");
            });
        });
    }

    @Test
    void testMavenWrapperProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/maven_wrapper");

        // When
        var result = generateSBOM(file, "examiner", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
                assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("examiner");
                assertThat(proper.sbom().getMetadata().getComponent().getGroup()).isEqualTo("io.github.heubeck");
                assertThat(proper.sbom().getMetadata().getComponent().getVersion()).isEqualTo("1.8.3");
            });
        });
    }

    @Test
    void testMavenFallbackProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/maven_fallback");

        // When
        var result = generateSBOM(file, "cdxgen-is-awesome", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
                assertThat(proper.sbom().getMetadata().getComponent().getName()).isEqualTo("cdxgen-is-awesome");
                assertThat(proper.sbom().getMetadata().getComponent().getGroup()).isEqualTo("cdxgen-test");
                assertThat(proper.sbom().getMetadata().getComponent().getVersion()).isEqualTo("1337");
                assertThat(proper.sbom().getComponents()).isNotEmpty();
            });
        });
    }

    @Test
    void testRecurseMixedProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/multi-mode");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, "multi-mode", Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
                assertThat(fallback.sbom().getMetadata().getComponent().getName()).isEqualTo("multi-mode");

                assertThat(fallback.sbom().getComponents()).flatExtracting(Component::getName).contains("husky", "quarkus-smallrye-health");
            });
        });
    }

    @Test
    void testNodeProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/node");

        // When
        var result = generateSBOM(file, "node", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
                assertThat(fallback.sbom().getComponents()).flatExtracting(Component::getName).containsOnly("husky");
            });
        });
    }

    @Test
    void testNoopProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/noop");

        // When
        var result = generateSBOM(file, "noop", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOf(CdxgenClient.SBOMGenerationResult.None.class);
        });
    }

    @Test
    void testGoProjectWithIssues() {
        // Given
        var file = Paths.get("src/test/resources/repo/go");

        // When
        var result = generateSBOM(file, "go", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
                // there are some license issues in this go.sum but license-fetch is disabled
                assertThat(fallback.validationIssues()).isEmpty();
            });
        });
    }

    @Test
    void testMultiModuleMavenNodeProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/multi-module-mode");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, "multi-module-mode", Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
                assertThat(fallback.sbom().getComponents()).flatExtracting(Component::getName).contains("remapping", "mutiny-kotlin");
            });
        });
    }

    @Test
    void testMultiModuleGradleProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/multi-gradle-module");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, "multi-gradle-module", Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Fallback.class, fallback -> {
                assertThat(fallback.sbom().getComponents()).flatExtracting(Component::getName).contains("ktor-client-serialization", "mimic-fn");
            });
        });
    }

    @ParameterizedTest
    @CsvSource({
        "gradle-single-module, false",
        "gradle-single-module-kt, false",
        "gradle-single-module, true",
        "gradle-single-module-kt, true"
    })
    void testSimpleGradleProject(String repoName, boolean recursive) {
        // Given
        var file = Paths.get("src/test/resources/repo/" + repoName);
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, recursive, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, repoName, Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Proper.class, proper -> {
                assertThat(proper.sbom().getComponents()).flatExtracting(Component::getName).contains("quarkus-core", "maven-model");
            });
        });
    }

    // TODO: split tests in command generation and command execution
    Result<CdxgenClient.SBOMGenerationResult> generateSBOM(Path file, String projectName, Optional<TechnolinatorConfig> config) {
        var metadata = new Command.Metadata("local", "local/test", "", Optional.empty());
        var cmd = cut.createCommand(file, projectName, false, config);

        return await(cmd.execute(metadata));
    }
}
