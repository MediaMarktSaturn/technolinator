package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

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
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                assertThat(yield.sbom().getMetadata().getComponent().getName()).isEqualTo("examiner");
            });
        });
    }

    @Test
    void testMavenWrapperProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/maven-wrapper");

        // When
        var result = generateSBOM(file, "examiner", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                assertThat(yield.sbom().getMetadata().getComponent().getName()).isEqualTo("examiner");
            });
        });
    }

    @Test
    void testIncompleteMavenFallbackProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/maven-incomplete");

        // When
        var result = generateSBOM(file, "cdxgen-is-awesome", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                assertThat(yield.sbom().getMetadata().getComponent().getName()).isEqualTo("cdxgen-is-awesome");
                assertThat(yield.sbom().getComponents()).isNotEmpty();
            });
        });
    }

    @Test
    void testRecurseMixedProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/multi-mode");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, false, false, false, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, "multi-mode", Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                assertThat(yield.sbom().getMetadata().getComponent().getName()).isEqualTo("multi-mode");

                assertThat(yield.sbom().getComponents()).flatExtracting(Component::getName).contains("husky", "quarkus-smallrye-health");
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
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield ->
                assertThat(yield.sbom().getComponents()).flatExtracting(Component::getName).containsOnly("husky")
            );
        });
    }

    @Test
    void testNoopProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/noop");

        // When
        var result = generateSBOM(file, "noop", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s ->
            assertThat(s.result()).isInstanceOf(CdxgenClient.SBOMGenerationResult.Yield.class)
        );
    }

    @Test
    void testGoProjectWithIssues() {
        // Given
        var file = Paths.get("src/test/resources/repo/go");

        // When
        var result = generateSBOM(file, "go", Optional.empty());

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield ->
                // there are some license issues in this go.sum but license-fetch is disabled
                assertThat(yield.validationIssues()).isEmpty()
            );
        });
    }

    @Test
    void testMultiModuleMavenNodeProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/multi-module-mode");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, false, false, false, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, "multi-module-mode", Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield ->
                assertThat(yield.sbom().getComponents()).flatExtracting(Component::getName).contains("remapping", "mutiny-kotlin")
            );
        });
    }

    @Test
    void testMultiModuleGradleProject() {
        // Given
        var file = Paths.get("src/test/resources/repo/multi-gradle-module");
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, true, false, false, false, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, "multi-gradle-module", Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield ->
                assertThat(yield.sbom().getComponents()).flatExtracting(Component::getName).contains("spring-boot-autoconfigure", "mimic-fn")
            );
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
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, recursive, false, false, false, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, repoName, Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield ->
                assertThat(yield.sbom().getComponents()).flatExtracting(Component::getName).contains("quarkus-core", "maven-model")
            );
        });
    }

    @ParameterizedTest
    @CsvSource({
        "gradle-jar-module, true",
        "gradle-jar-module, false"
    })
    void testGradleJarQualifierProject(String repoName, boolean recursive) {
        // Given
        var file = Paths.get("src/test/resources/repo/" + repoName);
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, recursive, false, false, false, List.of())).enable(true).build();

        // When
        var result = generateSBOM(file, repoName, Optional.of(config));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, s -> {
            assertThat(s.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield ->
                assertThat(yield.sbom().getComponents()).flatExtracting(Component::getName).contains("spring-boot-starter-logging", "micrometer-commons")
            );
        });
    }

    Result<CdxgenClient.SBOMGenerationResult> generateSBOM(Path file, String projectName, Optional<TechnolinatorConfig> config) {
        var metadata = new Command.Metadata("local", "local/test", "", Optional.empty());
        var cmds = cut.createCommands(file, projectName, false, config);

        return await(cmds.get(0).execute(metadata));
    }
}
