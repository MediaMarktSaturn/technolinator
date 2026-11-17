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
 * Failure reporting for pull-requests: the one status comment per pull-request, driven through the real analysis
 * handler against a fake GitHub. The SBOM creation is scripted, so these tests don't need cdxgen and friends.
 */
class PullRequestReportingTest extends GitHubReportingTestSupport {

    private static final String FAILED_HEADER = "❌ Technolinator could not create the SBOM";
    private static final String RECOVERED_HEADER = "🎉 Technolinator can analyse this pull request again";
    private static final String SIGNATURE = "[//]: # (Technolinator)";

    private long postsToComments(int number) {
        return github.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals("/repos/acme/shop/issues/" + number + "/comments")).count();
    }

    private long updatesOfComments() {
        return github.requests().stream().filter(r -> r.method().equals("PATCH") && r.path().contains("/issues/comments/")).count();
    }

    private double errorCount(String op) {
        var counter = meters.find("failure_report_errors").tag("op", op).counter();
        return counter == null ? 0 : counter.count();
    }

    private List<FakeGitHubServer.Comment> botComments(int number) {
        return github.commentsOf(number).stream().filter(c -> c.author.equals(BOT)).toList();
    }

    private FakeGitHubServer.Comment botComment(int number) {
        assertThat(botComments(number)).hasSize(1);
        return botComments(number).getFirst();
    }

    private static StatusMarker markerOf(FakeGitHubServer.Comment comment) {
        return StatusMarker.parse(comment.body).orElseThrow();
    }

    private static String statusComment(StatusMarker.State state, Instant started) {
        return new StatusMarker(state, started, "old0001", "deadbeef").render() + "\n\n" + FAILED_HEADER + "\n\n" + SIGNATURE;
    }

    private void breakMaven() {
        sbomCreation(mavenCannotResolve("shop"));
    }

    private void fixEverything() {
        sbomCreation(sbomCreated("shop"));
    }

    private void timeout(FakeGitHubServer.Pull pull) throws IOException {
        await(failureReporter.onPullRequestTimeout(context("refs/heads/" + pull.headRef, pull.headSha), freshRepository().getPullRequest(pull.number)));
    }

    @Nested
    @DisplayName("when the SBOM creation fails")
    class Failing {

        @Test
        void commentsOnThePullRequestAndCreatesNoIssue() throws IOException {
            github.setCodeOwners("* @org/team @alice\n");
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();

            analysePullRequest(pull);

            var comment = botComment(pull.number);
            assertThat(comment.body)
                .contains(FAILED_HEADER)
                .contains("`shop`")
                .contains("probably `maven`")
                .contains("Log excerpt")
                .contains("Could not resolve dependencies")
                .contains("pr0001")
                .contains(StatusReportRenderer.CONFIG_DOCS_URL)
                .contains("@copilot Technolinator could not create the SBOM")
                .doesNotContain(SECRET)
                .endsWith(SIGNATURE);
            assertThat(markerOf(comment).state()).isEqualTo(StatusMarker.State.FAILED);
            assertThat(github.issues()).isEmpty();
        }


        @Test
        void updatesTheSameCommentWithEveryTry() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();

            analysePullRequest(pull);
            var first = botComment(pull.number);
            pull.headSha = "pr0002";
            analysePullRequest(pull);
            pull.headSha = "pr0003";
            analysePullRequest(pull);

            assertThat(github.commentsOf(pull.number)).hasSize(1);
            assertThat(postsToComments(pull.number)).isEqualTo(1);
            assertThat(updatesOfComments()).isEqualTo(2);
            var comment = botComment(pull.number);
            assertThat(comment.id).isEqualTo(first.id);
            assertThat(comment.body).contains("pr0003").doesNotContain("pr0001");
        }

        @Test
        void keepsOneCommentPerPullRequest() throws IOException {
            var one = github.openPullRequest("feature/x", "pr0001");
            var two = github.openPullRequest("feature/y", "pr0002");
            breakMaven();

            analysePullRequest(one);
            analysePullRequest(two);
            analysePullRequest(one);

            assertThat(github.commentsOf(one.number)).hasSize(1);
            assertThat(github.commentsOf(two.number)).hasSize(1);
        }

        @Test
        void becomesTheRecoveryCommentWithTheReportWhenFixed() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();
            analysePullRequest(pull);
            var first = botComment(pull.number);
            fixEverything();
            pull.headSha = "pr0002";

            var result = analysePullRequest(pull);

            assertThat(result).isInstanceOf(Result.Success.class);
            var comment = botComment(pull.number);
            assertThat(comment.id).isEqualTo(first.id);
            assertThat(comment.body)
                .contains(RECOVERED_HEADER)
                .contains("Vulnerability report")
                .doesNotContain("DEPENDENCY_TRACK_URL")
                .contains("https://dtrack.example.com/projects?searchText=")
                .doesNotContain("Log excerpt");
            assertThat(markerOf(comment).state()).isEqualTo(StatusMarker.State.RECOVERED);
        }

        @Test
        void turnsIntoAFailureAgainWhenItBreaksAgain() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();
            analysePullRequest(pull);
            fixEverything();
            analysePullRequest(pull);
            breakMaven();
            pull.headSha = "pr0004";

            analysePullRequest(pull);

            var comment = botComment(pull.number);
            assertThat(comment.body).contains(FAILED_HEADER).contains("pr0004").doesNotContain(RECOVERED_HEADER);
            assertThat(markerOf(comment).state()).isEqualTo(StatusMarker.State.FAILED);
            assertThat(postsToComments(pull.number)).isEqualTo(1);
        }

        @Test
        void showsTheFailingModuleOfAMultiModuleRepository() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            sbomCreation(sbomCreated("shop-api"), cdxgenFails("shop-web", "npm ERR! code ERESOLVE", "npm ERR! Could not resolve dependency"));

            analysePullRequest(pull);

            var comment = botComment(pull.number);
            assertThat(comment.body).contains(FAILED_HEADER).contains("`shop-web`").contains("ERESOLVE");
            assertThat(comment.body).doesNotContain("`shop-api`");
        }
    }

    @Nested
    @DisplayName("when the analysis works")
    class Working {

        @Test
        void postsThePlainReportIfItNeverFailed() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            fixEverything();

            var result = analysePullRequest(pull);

            assertThat(result).isInstanceOf(Result.Success.class);
            var comment = botComment(pull.number);
            assertThat(comment.body).contains("Vulnerability report").doesNotContain(StatusMarker.PREFIX).doesNotContain(RECOVERED_HEADER);
        }

        @Test
        void writesNothingIfThereIsNothingToReport() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            sbomCreation(nothingToAnalyse());

            analysePullRequest(pull);

            assertThat(github.writes()).isEmpty();
        }

        @Test
        void doesNotTouchTheStatusIssueOfTheDefaultBranch() throws IOException {
            breakMaven();
            pushToDefaultBranch();
            var issue = github.openIssues().getFirst();
            var pull = github.openPullRequest("feature/x", "pr0001");
            fixEverything();

            analysePullRequest(pull);

            assertThat(github.issue(issue.number).open).isTrue();
            assertThat(github.commentsOf(issue.number)).isEmpty();
        }
    }

    @Nested
    @DisplayName("keeping one status comment per pull-request")
    class SingleComment {

        @Test
        void leavesCommentsOfOthersAloneEvenIfTheyCarryTheMarker() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            var foreign = github.seedComment(pull.number, "mallory", statusComment(StatusMarker.State.FAILED, Instant.parse("2999-01-01T00:00:00Z")));
            breakMaven();

            analysePullRequest(pull);

            assertThat(github.commentsOf(pull.number)).hasSize(2);
            assertThat(github.commentsOf(pull.number).stream().filter(c -> c.id == foreign.id).findFirst().orElseThrow().body)
                .isEqualTo(statusComment(StatusMarker.State.FAILED, Instant.parse("2999-01-01T00:00:00Z")));
            assertThat(botComments(pull.number)).hasSize(1);
        }

        @Test
        void removesDuplicatesOfItsOwnAndKeepsCommentsOfPeople() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            var kept = github.seedComment(pull.number, BOT, statusComment(StatusMarker.State.FAILED, Instant.parse("2026-01-01T00:00:00Z")));
            github.seedComment(pull.number, BOT, statusComment(StatusMarker.State.FAILED, Instant.parse("2026-01-01T00:00:00Z")));
            var human = github.seedComment(pull.number, "alice", "Looks good to me, " + StatusMarker.PREFIX + " is a funny thing");
            var chat = github.seedComment(pull.number, "bob", "Why does Technolinator fail?");
            breakMaven();

            analysePullRequest(pull);

            assertThat(botComments(pull.number)).singleElement().satisfies(comment -> {
                assertThat(comment.id).isEqualTo(kept.id);
                assertThat(comment.body).contains("pr0001");
            });
            assertThat(github.commentsOf(pull.number)).extracting(c -> c.id).contains(human.id, chat.id);
            assertThat(github.commentsOf(pull.number)).hasSize(3);
        }

        @Test
        void doesNotOverwriteTheResultOfAnAnalysisWhichStartedLater() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            var body = statusComment(StatusMarker.State.FAILED, Instant.parse("2999-01-01T00:00:00Z"));
            var newer = github.seedComment(pull.number, BOT, body);
            breakMaven();

            analysePullRequest(pull);

            assertThat(github.writes()).isEmpty();
            assertThat(botComment(pull.number).body).isEqualTo(body);
            assertThat(botComment(pull.number).id).isEqualTo(newer.id);
        }
    }

    @Nested
    @DisplayName("for pull-requests from forks")
    class Forks {

        @Test
        void showsNoLogsByDefault() throws IOException {
            var pull = github.openForkPullRequest("feature/x", "pr0001", "mallory");
            breakMaven();

            analysePullRequest(pull);

            assertThat(botComment(pull.number).body)
                .contains(FAILED_HEADER)
                .contains("Logs are not shown for pull requests from forks")
                .doesNotContain("Log excerpt")
                .doesNotContain("Could not resolve dependencies")
                .doesNotContain("AI coding agents")
                .contains(StatusReportRenderer.CONFIG_DOCS_URL);
        }

        @Test
        void showsLogsIfConfigured() throws IOException {
            useReporter(FailureReporter.Mode.ON, List.of(), true, 2);
            var pull = github.openForkPullRequest("feature/x", "pr0001", "mallory");
            breakMaven();

            analysePullRequest(pull);

            assertThat(botComment(pull.number).body)
                .contains("Log excerpt")
                .contains("Could not resolve dependencies")
                .doesNotContain(SECRET)
                .doesNotContain("Logs are not shown for pull requests from forks");
        }
    }

    @Nested
    @DisplayName("on infrastructure trouble")
    class Infrastructure {

        @Test
        void keepsTheCommentWhenTheCheckoutFails() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();
            analysePullRequest(pull);
            var before = botComment(pull.number).body;
            checkoutFails(new IllegalStateException("clone failed"));
            github.clearRequests();

            analysePullRequest(pull);

            assertThat(github.writes()).isEmpty();
            assertThat(botComment(pull.number).body).isEqualTo(before);
        }

        @Test
        void ignoresDependencyTrackBecausePullRequestsAreNotUploaded() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();
            analysePullRequest(pull);
            fixEverything();
            uploadFails(new IllegalStateException("Dependency-Track is down"));

            analysePullRequest(pull);

            assertThat(botComment(pull.number).body).contains(RECOVERED_HEADER);
        }
    }

    @Nested
    @DisplayName("in other configurations")
    class Switches {

        @Test
        void canBeSwitchedOffPerRepository() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();

            analysePullRequest(pull, Optional.of(configWithReporting(null, false)));

            assertThat(github.commentsOf(pull.number)).isEmpty();
            assertThat(github.writes()).isEmpty();
        }

        @Test
        void canBeSwitchedOffGlobally() throws IOException {
            useReporter(FailureReporter.Mode.OFF, List.of(), false, 2);
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();

            analysePullRequest(pull);

            assertThat(github.commentsOf(pull.number)).isEmpty();
        }

        @Test
        void dryRunNeverWrites() throws IOException {
            useReporter(FailureReporter.Mode.DRY_RUN, List.of(), false, 2);
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();

            analysePullRequest(pull);

            assertThat(github.commentsOf(pull.number)).isEmpty();
            assertThat(github.writes()).isEmpty();
        }

        @Test
        void onlyReportsForTheConfiguredRepositories() throws IOException {
            useReporter(FailureReporter.Mode.ON, List.of("other-repo"), false, 2);
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();

            analysePullRequest(pull);

            assertThat(github.commentsOf(pull.number)).isEmpty();
        }

        @Test
        void keepsWorkingWhenIssuesAreDisabled() throws IOException {
            github.setHasIssues(false);
            var pull = github.openPullRequest("feature/x", "pr0001");
            breakMaven();

            analysePullRequest(pull);

            assertThat(botComments(pull.number)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("when GitHub misbehaves")
    class GitHubTrouble {

        @Test
        void retriesOnceOnServerErrors() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            github.failNext("POST", "/comments$", 502, "Bad Gateway");
            breakMaven();

            analysePullRequest(pull);

            assertThat(postsToComments(pull.number)).isEqualTo(2);
            assertThat(botComments(pull.number)).hasSize(1);
            assertThat(errorCount("pull_request")).isZero();
        }

        @Test
        void doesNotRetryMissingPermissionsAndKeepsTheAnalysisResult() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            github.failNext("POST", "/comments$", 403, "Resource not accessible by integration");
            breakMaven();

            var result = analysePullRequest(pull);

            assertThat(result).isInstanceOf(Result.Success.class);
            assertThat(postsToComments(pull.number)).isEqualTo(1);
            assertThat(github.commentsOf(pull.number)).isEmpty();
            assertThat(errorCount("pull_request")).isEqualTo(1);
        }

        @Test
        void triesAgainWithTheNextAnalysis() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            github.failNext("POST", "/comments$", 403, "Resource not accessible by integration");
            breakMaven();
            analysePullRequest(pull);

            analysePullRequest(pull);

            assertThat(botComments(pull.number)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("when the analysis does not finish in time")
    class Timeouts {

        @Test
        void reportsOnlyRepeatedTimeouts() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");

            timeout(pull);
            assertThat(github.writes()).isEmpty();

            timeout(pull);

            assertThat(botComment(pull.number).body)
                .contains("The analysis did not finish in time")
                .endsWith(SIGNATURE);
        }

        @Test
        void recoversWithTheNextSuccessfulAnalysis() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");
            timeout(pull);
            timeout(pull);
            var first = botComment(pull.number);
            fixEverything();

            analysePullRequest(pull);

            var comment = botComment(pull.number);
            assertThat(comment.id).isEqualTo(first.id);
            assertThat(comment.body).contains(RECOVERED_HEADER);
        }

        @Test
        void doesNotCreateIssuesForPullRequests() throws IOException {
            var pull = github.openPullRequest("feature/x", "pr0001");

            timeout(pull);
            timeout(pull);
            timeout(pull);

            assertThat(github.issues()).isEmpty();
        }
    }
}
