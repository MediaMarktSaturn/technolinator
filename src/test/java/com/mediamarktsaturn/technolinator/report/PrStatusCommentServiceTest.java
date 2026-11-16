package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.report.PrStatusCommentService.Action;
import static com.mediamarktsaturn.technolinator.report.PrStatusCommentService.COMMENT_MARKER;
import static com.mediamarktsaturn.technolinator.report.PrStatusCommentService.Outcome;
import static com.mediamarktsaturn.technolinator.report.TestReports.T0;
import static com.mediamarktsaturn.technolinator.report.TestReports.failure;
import static com.mediamarktsaturn.technolinator.report.TestReports.report;
import static org.assertj.core.api.Assertions.assertThat;

class PrStatusCommentServiceTest {

    private final PrStatusCommentService cut = new PrStatusCommentService();
    private final FakeCommentApi api = new FakeCommentApi();

    private static PrStatusCommentService.Update update(Outcome outcome, Instant started, String reportText, boolean statusEnabled, boolean includeLogs) {
        var failure = outcome == Outcome.FAILED ? report(started, "Could not resolve foo") : null;
        return new PrStatusCommentService.Update("acme/shop#7", outcome, failure, reportText, includeLogs, statusEnabled,
            started, Optional.of("sha-" + started.getEpochSecond()), Optional.of("technolinator[bot]"));
    }

    private static PrStatusCommentService.Update update(Outcome outcome, Instant started, String reportText) {
        return update(outcome, started, reportText, true, true);
    }

    @Test
    void successCreatesTheReportCommentAsBefore() throws IOException {
        var action = cut.update(api, update(Outcome.SUCCEEDED, T0, "## Report"));

        assertThat(action).isEqualTo(Action.CREATED);
        assertThat(api.onlyBody()).isEqualTo("## Report\n\n" + COMMENT_MARKER);
    }

    @Test
    void successWithoutReportAndWithoutCommentCreatesNothing() throws IOException {
        assertThat(cut.update(api, update(Outcome.SUCCEEDED, T0, null))).isEqualTo(Action.NOTHING);

        assertThat(api.comments).isEmpty();
    }

    @Test
    void legacyCommentIsUpdated() throws IOException {
        api.add("technolinator[bot]", "old report\n\n" + COMMENT_MARKER);

        var action = cut.update(api, update(Outcome.SUCCEEDED, T0, "new report"));

        assertThat(action).isEqualTo(Action.UPDATED);
        assertThat(api.onlyBody()).startsWith("new report");
    }

    @Test
    void commentsOfOthersAreIgnored() throws IOException {
        api.add("alice", "looks good");

        cut.update(api, update(Outcome.SUCCEEDED, T0, "report"));

        assertThat(api.comments).hasSize(2);
        assertThat(api.comments.getFirst().body()).isEqualTo("looks good");
    }

    @Test
    void failureCreatesCommentWithReportOfTheSuccessfulProjects() throws IOException {
        var action = cut.update(api, update(Outcome.FAILED, T0, "## Vulnerabilities of other module"));

        assertThat(action).isEqualTo(Action.CREATED);
        assertThat(api.onlyBody()).contains("could not create the SBOM").contains("Could not resolve foo")
            .contains("## Vulnerabilities of other module").endsWith(COMMENT_MARKER);
    }

    @Test
    void everyRetryUpdatesTheSameComment() throws IOException {
        cut.update(api, update(Outcome.FAILED, T0, null));
        cut.update(api, update(Outcome.FAILED, T0.plusSeconds(60), null));
        var action = cut.update(api, update(Outcome.FAILED, T0.plusSeconds(120), null));

        assertThat(action).isEqualTo(Action.UPDATED);
        assertThat(api.comments).hasSize(1);
        assertThat(StatusMarker.parse(api.onlyBody())).hasValueSatisfying(m -> assertThat(m.started()).isEqualTo(T0.plusSeconds(120)));
    }

    @Test
    void recoveryCongratulatesAndKeepsTheComment() throws IOException {
        cut.update(api, update(Outcome.FAILED, T0, null));

        var action = cut.update(api, update(Outcome.SUCCEEDED, T0.plusSeconds(60), "## Report"));

        assertThat(action).isEqualTo(Action.UPDATED);
        assertThat(api.comments).hasSize(1);
        assertThat(api.onlyBody()).contains("🎉").contains("## Report").endsWith(COMMENT_MARKER).doesNotContain("Could not resolve foo");
        assertThat(StatusMarker.parse(api.onlyBody())).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.RECOVERED));
    }

    @Test
    void recoveryWithoutReportShowsOnlyTheCongratulations() throws IOException {
        cut.update(api, update(Outcome.FAILED, T0, null));

        cut.update(api, update(Outcome.SUCCEEDED, T0.plusSeconds(60), null));

        assertThat(api.onlyBody()).contains("🎉").endsWith(COMMENT_MARKER);
    }

    @Test
    void congratulationsStayUntilThereIsANewReport() throws IOException {
        cut.update(api, update(Outcome.FAILED, T0, null));
        cut.update(api, update(Outcome.SUCCEEDED, T0.plusSeconds(60), null));

        cut.update(api, update(Outcome.SUCCEEDED, T0.plusSeconds(120), null));
        assertThat(api.onlyBody()).contains("🎉");

        cut.update(api, update(Outcome.SUCCEEDED, T0.plusSeconds(180), "## Fresh report"));
        assertThat(api.onlyBody()).startsWith("## Fresh report");
    }

    @Test
    void failingAgainAfterRecoveryUpdatesTheSameComment() throws IOException {
        cut.update(api, update(Outcome.FAILED, T0, null));
        cut.update(api, update(Outcome.SUCCEEDED, T0.plusSeconds(60), "## Report"));

        cut.update(api, update(Outcome.FAILED, T0.plusSeconds(120), null));

        assertThat(api.comments).hasSize(1);
        assertThat(api.onlyBody()).contains("could not create the SBOM");
    }

    @Test
    void inconclusiveResultDoesNotHideAFailureNorMistakeItForRecovery() throws IOException {
        cut.update(api, update(Outcome.FAILED, T0, null));
        var before = api.onlyBody();

        assertThat(cut.update(api, update(Outcome.UNKNOWN, T0.plusSeconds(60), "## Report"))).isEqualTo(Action.NOTHING);

        assertThat(api.onlyBody()).isEqualTo(before);
    }

    @Test
    void inconclusiveResultWithReportWorksAsBeforeIfThereWasNoFailure() throws IOException {
        assertThat(cut.update(api, update(Outcome.UNKNOWN, T0, "## Report"))).isEqualTo(Action.CREATED);
        assertThat(cut.update(api, update(Outcome.UNKNOWN, T0, null))).isEqualTo(Action.NOTHING);

        assertThat(api.onlyBody()).startsWith("## Report");
    }

    @Test
    void duplicatesOfTheBotAreDeleted() throws IOException {
        api.add("technolinator[bot]", "first\n\n" + COMMENT_MARKER);
        api.add("technolinator[bot]", "second\n\n" + COMMENT_MARKER);
        api.add("mallory", "copied\n\n" + COMMENT_MARKER);

        cut.update(api, update(Outcome.SUCCEEDED, T0, "report"));

        assertThat(api.comments).extracting(CommentApi.Comment::authorLogin).containsExactly("technolinator[bot]", "mallory");
        assertThat(api.comments.getFirst().body()).startsWith("report");
    }

    @Test
    void aCommentOfSomeoneElseCarryingTheMarkerIsNeitherUpdatedNorTrusted() throws IOException {
        api.add("mallory", "quoting: " + COMMENT_MARKER);

        var action = cut.update(api, update(Outcome.FAILED, T0, null));

        assertThat(action).isEqualTo(Action.CREATED);
        assertThat(api.comments).hasSize(2);
        assertThat(api.comments.getFirst().body()).isEqualTo("quoting: " + COMMENT_MARKER);
        assertThat(api.comments.getLast().authorLogin()).isEqualTo("technolinator[bot]");
    }

    @Test
    void aCommentOfSomeoneElseIsStillUpdatedWithoutStatusManagement() throws IOException {
        api.add("mallory", "old report\n\n" + COMMENT_MARKER);

        var action = cut.update(api, update(Outcome.SUCCEEDED, T0, "new report", false, true));

        assertThat(action).isEqualTo(Action.UPDATED);
        assertThat(api.onlyBody()).startsWith("new report");
    }

    @Test
    void duplicatesAreKeptWithoutStatusManagement() throws IOException {
        api.add("technolinator[bot]", "first\n\n" + COMMENT_MARKER);
        api.add("technolinator[bot]", "second\n\n" + COMMENT_MARKER);

        cut.update(api, update(Outcome.SUCCEEDED, T0, "report", false, true));

        assertThat(api.comments).hasSize(2);
    }

    @Test
    void forkPullRequestsGetNoLogs() throws IOException {
        var failure = failure("shop", "Could not resolve internal-thing", "[ERROR] internal-thing");
        var update = new PrStatusCommentService.Update("acme/shop#7", Outcome.FAILED, report(T0, "sha", failure), null,
            false, true, T0, Optional.empty(), Optional.empty());

        cut.update(api, update);

        assertThat(api.onlyBody()).doesNotContain("internal-thing").contains("Logs are not shown");
    }

    @Test
    void withoutStatusManagementFailuresAreNotShown() throws IOException {
        assertThat(cut.update(api, update(Outcome.FAILED, T0, null, false, true))).isEqualTo(Action.NOTHING);
        assertThat(cut.update(api, update(Outcome.FAILED, T0, "## Report of the others", false, true))).isEqualTo(Action.CREATED);

        assertThat(api.onlyBody()).isEqualTo("## Report of the others\n\n" + COMMENT_MARKER);
        assertThat(StatusMarker.isPresentIn(api.onlyBody())).isFalse();
    }

    @Test
    void resultOfAnOlderAnalysisIsIgnored() throws IOException {
        cut.update(api, update(Outcome.FAILED, T0.plusSeconds(600), null));
        var before = api.onlyBody();

        assertThat(cut.update(api, update(Outcome.SUCCEEDED, T0, "old report"))).isEqualTo(Action.OUTDATED);

        assertThat(api.onlyBody()).isEqualTo(before);
    }

    @Test
    void statusCommentDetectionToleratesTrailingWhitespaceAndNull() {
        assertThat(PrStatusCommentService.isStatusComment("x " + COMMENT_MARKER + "\n")).isTrue();
        assertThat(PrStatusCommentService.isStatusComment("x")).isFalse();
        assertThat(PrStatusCommentService.isStatusComment(null)).isFalse();
    }
}
