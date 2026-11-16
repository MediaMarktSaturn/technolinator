package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure reporting for the default branch: the one status issue, driven through the real analysis handler
 * against a fake GitHub. The SBOM creation is scripted, so these tests don't need cdxgen and friends.
 */
class DefaultBranchReportingTest extends GitHubReportingTestSupport {

    private static final String ISSUES_PATH = "/repos/acme/shop/issues";

    private long postsTo(String path) {
        return github.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals(path)).count();
    }

    private double errorCount(String op) {
        var counter = meters.find("failure_report_errors").tag("op", op).counter();
        return counter == null ? 0 : counter.count();
    }

    private static StatusMarker markerOf(FakeGitHubServer.Issue issue) {
        return StatusMarker.parse(issue.body).orElseThrow();
    }

    private void breakMaven() {
        sbomCreation(mavenCannotResolve("shop"));
    }

    private void fixEverything() {
        sbomCreation(sbomCreated("shop"));
    }

    @Nested
    @DisplayName("when the SBOM creation fails")
    class Failing {

        @Test
        void createsOneLabelledStatusIssueWithTheLogs() throws IOException {
            github.setCodeOwners("* @org/team @alice\n");
            breakMaven();

            var result = pushToDefaultBranch();

            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(github.openIssues()).hasSize(1);
            var issue = github.openIssues().getFirst();
            assertThat(issue.title).isEqualTo("Technolinator: SBOM creation fails on main");
            assertThat(issue.author).isEqualTo(BOT);
            assertThat(issue.labels).containsExactly("technolinator");
            assertThat(github.labels()).contains("technolinator");
            assertThat(issue.assignees).containsExactly("alice");
            assertThat(issue.body)
                .contains("`shop`")
                .contains("probably `maven`")
                .contains("Log excerpt")
                .contains("Could not resolve dependencies")
                .contains("[`c0ffee01`](https://github.com/acme/shop/commit/c0ffee01)")
                .contains("cc @org/team");
            assertThat(markerOf(issue).state()).isEqualTo(StatusMarker.State.FAILED);
            assertThat(github.commentsOf(issue.number)).isEmpty();
        }

        @Test
        void neverLeaksSecretsIntoTheIssue() throws IOException {
            breakMaven();

            pushToDefaultBranch();

            var issue = github.openIssues().getFirst();
            assertThat(issue.body).doesNotContain(SECRET);
            assertThat(github.commentsOf(issue.number)).noneMatch(c -> c.body.contains(SECRET));
        }

        @Test
        void describesGradleFailuresToo() throws IOException {
            sbomCreation(gradleCannotResolve("shop"));

            pushToDefaultBranch();

            assertThat(github.openIssues()).singleElement().satisfies(issue ->
                assertThat(issue.body).contains("probably `gradle`").contains("Could not resolve all files"));
        }

        @Test
        void reusesTheIssueForRepeatedFailures() throws IOException {
            breakMaven();

            pushToDefaultBranch();
            var first = github.openIssues().getFirst();
            pushToDefaultBranch();
            pushToDefaultBranch();

            assertThat(github.issues()).hasSize(1);
            assertThat(postsTo(ISSUES_PATH)).isEqualTo(1);
            var issue = github.openIssues().getFirst();
            assertThat(issue.number).isEqualTo(first.number);
            assertThat(issue.body).contains("c0ffee03");
        }

        @Test
        void tellsInACommentWhenTheCauseChanges() throws IOException {
            breakMaven();
            pushToDefaultBranch();
            sbomCreation(gradleCannotResolve("shop"));

            pushToDefaultBranch();

            var issue = github.openIssues().getFirst();
            assertThat(github.issues()).hasSize(1);
            assertThat(issue.body).contains("Could not resolve all files");
            assertThat(github.commentsOf(issue.number)).singleElement()
                .satisfies(comment -> assertThat(comment.body).contains("The cause of the failure changed"));
        }

        @Test
        void reportsOnlyTheFailingModuleOfAMultiModuleRepository() throws IOException {
            sbomCreation(sbomCreated("shop-api"), cdxgenFails("shop-web", "npm ERR! code ERESOLVE", "npm ERR! Could not resolve dependency"));

            pushToDefaultBranch();

            assertThat(github.openIssues()).singleElement().satisfies(issue -> {
                assertThat(issue.body).contains("`shop-web`").contains("ERESOLVE");
                assertThat(issue.body).doesNotContain("`shop-api`");
            });
        }
    }

    @Nested
    @DisplayName("when the analysis works again")
    class Recovering {

        @Test
        void closesTheIssueAsResolvedAndSaysSo() throws IOException {
            breakMaven();
            pushToDefaultBranch();
            var number = github.openIssues().getFirst().number;
            fixEverything();

            var result = pushToDefaultBranch();

            assertThat(result).isInstanceOf(Result.Success.class);
            assertThat(github.openIssues()).isEmpty();
            var issue = github.issue(number);
            assertThat(issue.open).isFalse();
            assertThat(issue.stateReason).isEqualTo("completed");
            assertThat(issue.body).contains("Resolved on");
            assertThat(markerOf(issue).state()).isEqualTo(StatusMarker.State.RESOLVED);
            assertThat(github.commentsOf(number)).singleElement()
                .satisfies(comment -> assertThat(comment.body).contains("SBOM creation succeeded again"));
        }

        @Test
        void opensANewIssueForTheNextFailure() throws IOException {
            breakMaven();
            pushToDefaultBranch();
            var first = github.openIssues().getFirst().number;
            fixEverything();
            pushToDefaultBranch();
            breakMaven();

            pushToDefaultBranch();

            assertThat(github.issues()).hasSize(2);
            assertThat(github.openIssues()).singleElement().satisfies(issue -> {
                assertThat(issue.number).isNotEqualTo(first);
                assertThat(markerOf(issue).state()).isEqualTo(StatusMarker.State.FAILED);
            });
            assertThat(github.issue(first).open).isFalse();
        }

        @Test
        void writesNothingWhenThereWasNoFailure() throws IOException {
            fixEverything();

            pushToDefaultBranch();
            pushToDefaultBranch();

            assertThat(github.writes()).isEmpty();
        }

        @Test
        void writesNothingWhenThereIsNothingToAnalyse() throws IOException {
            sbomCreation(nothingToAnalyse());

            pushToDefaultBranch();

            assertThat(github.writes()).isEmpty();
        }

        @Test
        void doesNotTreatAFailedUploadAsRecovery() throws IOException {
            breakMaven();
            pushToDefaultBranch();
            var issueBefore = github.openIssues().getFirst();
            fixEverything();
            uploadFails(new IllegalStateException("Dependency-Track is down"));
            github.clearRequests();

            pushToDefaultBranch();

            assertThat(github.writes()).isEmpty();
            assertThat(github.issue(issueBefore.number).open).isTrue();
            assertThat(github.issue(issueBefore.number).body).isEqualTo(issueBefore.body);

            uploadWorks();
            pushToDefaultBranch();

            assertThat(github.openIssues()).isEmpty();
        }

        @Test
        void keepsTheIssueWhenTheCheckoutFailsAndWritesNothing() throws IOException {
            breakMaven();
            pushToDefaultBranch();
            var number = github.openIssues().getFirst().number;
            var bodyBefore = github.issue(number).body;
            checkoutFails(new IllegalStateException("clone failed"));
            github.clearRequests();

            var result = pushToDefaultBranch();

            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(github.writes()).isEmpty();
            assertThat(github.issue(number).open).isTrue();
            assertThat(github.issue(number).body).isEqualTo(bodyBefore);
        }
    }

    @Nested
    @DisplayName("keeping at most one open status issue")
    class SingleIssue {

        private String statusBody(String sha) {
            return new StatusMarker(StatusMarker.State.FAILED, Instant.parse("2026-01-01T00:00:00Z"), sha, "deadbeef").render()
                + "\n\n# Technolinator could not create the SBOM\n";
        }

        @Test
        void closesDuplicatesInFavourOfTheOldestOne() throws IOException {
            var oldest = github.seedIssue(BOT, "Technolinator: SBOM creation fails on main", statusBody("a1"), true);
            var duplicate = github.seedIssue(BOT, "Technolinator: SBOM creation fails on main", statusBody("a2"), true);
            breakMaven();

            pushToDefaultBranch();

            assertThat(github.openIssues()).singleElement().satisfies(issue -> assertThat(issue.number).isEqualTo(oldest.number));
            assertThat(github.issue(duplicate.number).open).isFalse();
            assertThat(github.issue(duplicate.number).stateReason).isEqualTo("not_planned");
            assertThat(github.commentsOf(duplicate.number)).singleElement()
                .satisfies(comment -> assertThat(comment.body).contains("Closing as duplicate of #" + oldest.number));
            assertThat(postsTo(ISSUES_PATH)).isZero();
        }

        @Test
        void ignoresIssuesOfOthersEvenIfTheyCarryTheMarker() throws IOException {
            var foreign = github.seedIssue("mallory", "Technolinator: SBOM creation fails on main", statusBody("evil"), true);
            breakMaven();

            pushToDefaultBranch();

            assertThat(github.issue(foreign.number).open).isTrue();
            assertThat(github.issue(foreign.number).body).isEqualTo(statusBody("evil"));
            assertThat(github.commentsOf(foreign.number)).isEmpty();
            assertThat(github.openIssues()).filteredOn(issue -> issue.author.equals(BOT)).hasSize(1);
        }

        @Test
        void ignoresIssuesOfOthersWhenGitHubIgnoresTheCreatorFilter() throws IOException {
            github.ignoreCreatorFilter();
            var foreign = github.seedIssue("mallory", "Technolinator: SBOM creation fails on main", statusBody("evil"), true);
            breakMaven();

            pushToDefaultBranch();
            fixEverything();
            pushToDefaultBranch();

            assertThat(github.issue(foreign.number).open).isTrue();
            assertThat(github.issue(foreign.number).body).isEqualTo(statusBody("evil"));
            assertThat(github.commentsOf(foreign.number)).isEmpty();
        }

        @Test
        void ignoresUnrelatedIssuesOfTheBot() throws IOException {
            var other = github.seedIssue(BOT, "Something else", "no marker here", true);
            breakMaven();

            pushToDefaultBranch();

            assertThat(github.issue(other.number).body).isEqualTo("no marker here");
            assertThat(github.openIssues()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("when a person closes the status issue")
    class ClosedByHand {

        @Test
        void staysQuietUntilTheAnalysisHasWorkedAgain() throws IOException {
            breakMaven();
            pushToDefaultBranch();
            var number = github.openIssues().getFirst().number;
            github.closeManually(number);
            github.clearRequests();

            pushToDefaultBranch();
            pushToDefaultBranch();

            assertThat(github.writes()).isEmpty();
            assertThat(github.openIssues()).isEmpty();

            fixEverything();
            pushToDefaultBranch();
            breakMaven();
            pushToDefaultBranch();

            assertThat(github.issues()).hasSize(2);
            assertThat(github.openIssues()).singleElement().satisfies(issue -> assertThat(issue.number).isNotEqualTo(number));
        }
    }

    @Nested
    @DisplayName("on other branches and in other configurations")
    class Scope {

        @Test
        void ignoresFailuresOnFeatureBranches() throws IOException {
            breakMaven();

            pushTo("refs/heads/feature/x", nextCommit(), Optional.empty());

            assertThat(github.requests().stream().filter(r -> r.path().startsWith(ISSUES_PATH))).isEmpty();
            assertThat(github.issues()).isEmpty();
        }

        @Test
        void doesNotTouchIssuesOfRepositoriesWithoutIssues() throws IOException {
            github.setHasIssues(false);
            breakMaven();

            var result = pushToDefaultBranch();

            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(github.requests().stream().filter(r -> r.path().startsWith(ISSUES_PATH))).isEmpty();
        }

        @Test
        void doesNotTouchIssuesOfArchivedRepositories() throws IOException {
            github.setArchived(true);
            breakMaven();

            pushToDefaultBranch();

            assertThat(github.requests().stream().filter(r -> r.path().startsWith(ISSUES_PATH))).isEmpty();
        }

        @Test
        void canBeSwitchedOffPerRepository() throws IOException {
            breakMaven();

            pushToDefaultBranch(configWithReporting(false, null));

            assertThat(github.issues()).isEmpty();
            assertThat(github.requests().stream().filter(r -> r.path().startsWith(ISSUES_PATH))).isEmpty();
        }

        @Test
        void canBeSwitchedOffPerRepositoryWithoutDisablingPullRequestComments() throws IOException {
            breakMaven();
            var pull = github.openPullRequest("feature/x", "pr0001");

            analysePullRequest(pull, Optional.of(configWithReporting(false, true)));

            assertThat(github.issues()).isEmpty();
            assertThat(github.commentsOf(pull.number)).hasSize(1);
        }

        @Test
        void canBeSwitchedOffGlobally() throws IOException {
            useReporter(FailureReporter.Mode.OFF, List.of(), false, 2);
            breakMaven();

            var result = pushToDefaultBranch();

            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(github.requests().stream().filter(r -> r.path().startsWith(ISSUES_PATH))).isEmpty();
        }

        @Test
        void onlyReportsForTheConfiguredRepositories() throws IOException {
            useReporter(FailureReporter.Mode.ON, List.of("other-repo"), false, 2);
            breakMaven();

            pushToDefaultBranch();
            assertThat(github.issues()).isEmpty();

            useReporter(FailureReporter.Mode.ON, List.of("shop"), false, 2);
            pushToDefaultBranch();
            assertThat(github.openIssues()).hasSize(1);
        }

        @Test
        void dryRunReadsButNeverWrites() throws IOException {
            useReporter(FailureReporter.Mode.DRY_RUN, List.of(), false, 2);
            breakMaven();

            var result = pushToDefaultBranch();

            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(github.writes()).isEmpty();
            assertThat(github.requests()).anyMatch(r -> r.method().equals("GET") && r.path().equals(ISSUES_PATH));
        }
    }

    @Nested
    @DisplayName("when GitHub misbehaves")
    class GitHubTrouble {

        @Test
        void retriesOnceOnServerErrors() throws IOException {
            github.failNext("POST", "/issues$", 502, "Bad Gateway");
            breakMaven();

            pushToDefaultBranch();

            assertThat(postsTo(ISSUES_PATH)).isEqualTo(2);
            assertThat(github.openIssues()).hasSize(1);
            assertThat(errorCount("issue")).isZero();
        }

        @Test
        void doesNotRetryMissingPermissionsAndKeepsTheAnalysisResult() throws IOException {
            github.failNext("POST", "/issues$", 403, "Resource not accessible by integration");
            breakMaven();

            var result = pushToDefaultBranch();

            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(postsTo(ISSUES_PATH)).isEqualTo(1);
            assertThat(github.issues()).isEmpty();
            assertThat(errorCount("issue")).isEqualTo(1);
        }

        @Test
        void triesAgainWithTheNextAnalysis() throws IOException {
            github.failNext("POST", "/issues$", 403, "Resource not accessible by integration");
            breakMaven();
            pushToDefaultBranch();

            pushToDefaultBranch();

            assertThat(github.openIssues()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("when the analysis does not finish in time")
    class Timeouts {

        private void timeout() throws IOException {
            await(failureReporter.onDefaultBranchTimeout(context("refs/heads/main", nextCommit())));
        }

        @Test
        void reportsOnlyRepeatedTimeouts() throws IOException {
            timeout();
            assertThat(github.writes()).isEmpty();

            timeout();

            assertThat(github.openIssues()).singleElement().satisfies(issue ->
                assertThat(issue.body).contains("The analysis did not finish in time"));
        }

        @Test
        void countsOnlyTimeoutsInARow() throws IOException {
            timeout();
            fixEverything();
            pushToDefaultBranch();

            timeout();

            assertThat(github.issues()).isEmpty();
        }

        @Test
        void resolvesAReportedTimeoutWithTheNextSuccess() throws IOException {
            timeout();
            timeout();
            assertThat(github.openIssues()).hasSize(1);
            fixEverything();

            pushToDefaultBranch();

            assertThat(github.openIssues()).isEmpty();
            assertThat(github.issues()).singleElement().satisfies(issue ->
                assertThat(markerOf(issue).state()).isEqualTo(StatusMarker.State.RESOLVED));
        }
    }
}
