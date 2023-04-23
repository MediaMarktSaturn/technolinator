package com.mediamarktsaturn.technolinator.sbom;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.technolinator.Result;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class GrypeClientTest {

    @Inject
    GrypeClient cut;

    @Test
    void testReportForVulnerableSbom() {
        // Given
        var sbom = Paths.get("src/test/resources/sbom/vulnerable.json");

        // When
        var result = await(cut.createVulnerabilityReport(sbom));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(GrypeClient.VulnerabilityReport.Report.class, report -> {
                assertThat(report.text()).contains(
                    "Vulnerability Report",
                    "---",
                    "netty-codec-haproxy",
                    "netty-codec-http",
                    "vertx-web"
                );
            });
        });
    }

    @Test
    void testReportForNotVulnerableSbom() {
        // Given
        var sbom = Paths.get("src/test/resources/sbom/not-vulnerable.json");

        // When
        var result = await(cut.createVulnerabilityReport(sbom));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(GrypeClient.VulnerabilityReport.Report.class, report -> {
                assertThat(report.text()).contains(
                    "No vulnerabilities found"
                );
            });
        });
    }

    @Test
    void testReportForEmptySbom() {
        // Given
        var sbom = Paths.get("src/test/resources/sbom/empty.json");

        // When
        var result = await(cut.createVulnerabilityReport(sbom));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Failure.class, failure -> {
            assertThat(failure.cause()).hasMessageEndingWith("exited with 1");
        });
    }

    @Test
    void testReportForMissingSbom() {
        // Given
        var sbom = Paths.get("src/test/resources/sbom/there-is-nothing-here.json");

        // When
        var result = await(cut.createVulnerabilityReport(sbom));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Failure.class, failure -> {
            assertThat(failure.cause()).hasMessageEndingWith("exited with 1");
        });
    }
}
