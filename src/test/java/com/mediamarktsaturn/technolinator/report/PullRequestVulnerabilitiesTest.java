package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilityReporting.VulnerabilityReport;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vulnerabilities the analysis of a pull-request tells the commit status about, driven through the real analysis handler.
 */
class PullRequestVulnerabilitiesTest extends GitHubReportingTestSupport {

    private static VulnerabilityReport report(String marker) {
        return VulnerabilityReport.report(
            "<!-- technolinator-severities %s -->\n⚠ **Vulnerability Report**\n\n| Artifact |".formatted(marker), "shop");
    }

    @Test
    void summarizesTheVulnerabilitiesOfTheSbom() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(sbomCreated("shop"));
        vulnerabilityReport(Result.success(report("critical=1 high=2 medium=3 low=4 unspecified=5")));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).contains(Result.success(new VulnerabilitySummary(1, 2, 3, 4, 5)));
    }

    @Test
    void sumsUpTheVulnerabilitiesOfAllModules() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(sbomCreated("shop-api"), sbomCreated("shop-web"));
        vulnerabilityReport(Result.success(report("critical=0 high=2 medium=0 low=1 unspecified=0")));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).contains(Result.success(new VulnerabilitySummary(0, 4, 0, 2, 0)));
    }

    @Test
    void reportWithoutFindingsHasNone() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(sbomCreated("shop"));
        vulnerabilityReport(Result.success(VulnerabilityReport.report("🏆 _No vulnerabilities found_", "shop")));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).contains(Result.success(VulnerabilitySummary.NONE));
    }

    @Test
    void failingAnalyzerMakesTheVulnerabilitiesUnknown() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(sbomCreated("shop"));
        var failure = new IllegalStateException("analyzer crashed");
        vulnerabilityReport(Result.failure(failure));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).contains(Result.failure(failure));
    }

    @Test
    void findingsWithoutSeveritiesAreUnknown() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(sbomCreated("shop"));
        vulnerabilityReport(Result.success(VulnerabilityReport.report("⚠ **Vulnerability Report**\n\n| Artifact |", "shop")));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).hasValueSatisfying(result -> assertThat(result).isInstanceOf(Result.Failure.class));
    }

    @Test
    void missingReportIsUnknown() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(sbomCreated("shop"));
        vulnerabilityReport(Result.success(VulnerabilityReport.none()));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).hasValueSatisfying(result -> assertThat(result).isInstanceOf(Result.Failure.class));
    }

    @Test
    void nothingToAnalyseHasNoVulnerabilities() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(nothingToAnalyse(), sbomCreated("shop"));
        vulnerabilityReport(Result.success(report("critical=0 high=1 medium=0 low=0 unspecified=0")));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).contains(Result.success(new VulnerabilitySummary(0, 1, 0, 0, 0)));
    }

    @Test
    void withoutSbomThereAreNoVulnerabilities() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(nothingToAnalyse());

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).contains(Result.success(VulnerabilitySummary.NONE));
    }

    @Test
    void failingSbomCreationMakesTheVulnerabilitiesUnknown() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(mavenCannotResolve("shop"));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).hasValueSatisfying(result -> assertThat(result).isInstanceOf(Result.Failure.class));
    }

    @Test
    void failingCheckoutMakesTheVulnerabilitiesUnknown() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        checkoutFails(new IOException("no checkout"));

        var outcome = analysePullRequestOutcome(pull);

        assertThat(outcome.vulnerabilities()).hasValueSatisfying(result -> assertThat(result).isInstanceOf(Result.Failure.class));
    }

    @Test
    void createsTheReportOnce() throws IOException {
        var pull = github.openPullRequest("feature/x", "pr0001");
        sbomCreation(sbomCreated("shop"));
        vulnerabilityReport(Result.success(report("critical=0 high=0 medium=0 low=0 unspecified=0")));

        analysePullRequestOutcome(pull);

        // the comment and the commit status are made of the same report
        assertThat(github.commentsOf(pull.number)).isNotEmpty();
        verifyReportsCreated(1);
    }

    @Test
    void analysisOfDefaultBranchDoesNotLookAtVulnerabilities() throws IOException {
        sbomCreation(sbomCreated("shop"));

        var result = pushToDefaultBranch();

        assertThat(result).isNotNull();
        verifyReportsCreated(0);
    }
}
