package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Disabled // disabled due to not yet reproducible build errors in github actions
class GrypeClientTest {

    @ConfigProperty(name = "grype.template")
    String template;

    private GrypeClient cut() {
        return new GrypeClient(Optional.of(template), Optional.empty());
    }

    @Test
    void testReportForVulnerableSbom() {
        // Given
        var sbom = Paths.get("src/test/resources/sbom/vulnerable.json");
        var projectName = UUID.randomUUID().toString();

        // When
        var result = await(cut().createVulnerabilityReport(sbom, projectName));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(VulnerabilityReporting.VulnerabilityReport.Report.class, report -> {
                assertThat(report.projectName()).hasToString(projectName);
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
        var result = await(cut().createVulnerabilityReport(sbom, "myProject"));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(VulnerabilityReporting.VulnerabilityReport.Report.class, report -> {
                assertThat(report.projectName()).hasToString("myProject");
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
        var result = await(cut().createVulnerabilityReport(sbom, "myProject"));

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
        var result = await(cut().createVulnerabilityReport(sbom, "myProject"));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Failure.class, failure -> {
            assertThat(failure.cause()).hasMessageEndingWith("exited with 1");
        });
    }
}
