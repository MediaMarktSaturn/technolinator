package com.mediamarktsaturn.technolinator.sbom;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mediamarktsaturn.technolinator.Result;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class SbomqsClientTest {

    @Inject
    SbomqsClient cut;

    @ParameterizedTest
    @CsvSource({
        "src/test/resources/sbom/vulnerable.json",
        "src/test/resources/sbom/not-vulnerable.json",
    })
    void testSuccessfulScoring(String sbomFile) {
        // Given
        var sbom = Paths.get(sbomFile);

        // When
        var result = await(cut.calculateQualityScore(sbom));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(SbomqsClient.QualityScore.class, qualityScore -> {
                assertThat(qualityScore.score()).isNotBlank().satisfies(score -> {
                    var parsed = Double.parseDouble(score);
                    assertThat(parsed).isBetween(0.1, 9.9);
                });
            });
        });
    }

    @ParameterizedTest
    @CsvSource({
        "src/test/resources/sbom/empty.json",
        "src/test/resources/sbom/unkown.json",
        "src/test/resources/sbom/there-is-nothing-here.json"
    })
    void testFailedScoring(String sbomFile) {
        // Given
        var sbom = Paths.get(sbomFile);

        // When
        var result = await(cut.calculateQualityScore(sbom));

        // Then
        assertThat(result).isInstanceOf(Result.Failure.class);
    }
}
