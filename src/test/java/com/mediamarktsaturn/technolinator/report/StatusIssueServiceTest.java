package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.HttpException;

import java.io.IOException;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.report.StatusIssueService.Action;
import static com.mediamarktsaturn.technolinator.report.TestReports.REPO;
import static com.mediamarktsaturn.technolinator.report.TestReports.T0;
import static com.mediamarktsaturn.technolinator.report.TestReports.report;
import static org.assertj.core.api.Assertions.assertThat;

class StatusIssueServiceTest {

    private final MutableClock clock = new MutableClock(T0.plusSeconds(3600));
    private final StatusIssueService cut = new StatusIssueService(clock);
    private final FakeIssueApi api = new FakeIssueApi();

    @BeforeEach
    void setUp() {
        api.codeOwners = "* @alice @acme/platform";
    }

    @Test
    void failureCreatesTheIssue() throws IOException {
        var action = cut.onFailure(api, report(T0, "boom"));

        assertThat(action).isEqualTo(Action.CREATED);
        assertThat(api.issues).hasSize(1);
        var issue = api.get(1);
        assertThat(issue.title).isEqualTo("Technolinator: SBOM creation fails on main");
        assertThat(issue.assignees).containsExactly("alice");
        assertThat(issue.body).contains("boom").contains("cc @acme/platform");
        assertThat(StatusMarker.parse(issue.body)).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.FAILED));
    }

    @Test
    void repeatedFailureUpdatesTheSameIssue() throws IOException {
        cut.onFailure(api, report(T0, "boom"));

        var action = cut.onFailure(api, report(T0.plusSeconds(600), "boom"));

        assertThat(action).isEqualTo(Action.UPDATED);
        assertThat(api.issues).hasSize(1);
        assertThat(api.get(1).comments).isEmpty();
        assertThat(StatusMarker.parse(api.get(1).body)).hasValueSatisfying(m -> assertThat(m.started()).isEqualTo(T0.plusSeconds(600)));
        assertThat(api.get(1).body).contains("Failing since | 2026-01-01 01:00 UTC");
    }

    @Test
    void identicalFailureDoesNotWrite() throws IOException {
        var report = report(T0, "boom");
        cut.onFailure(api, report);
        // the first update replaces the time the failure was noticed by the creation time of the issue
        cut.onFailure(api, report);
        api.writes.clear();

        cut.onFailure(api, report);
        // the first update replaces the time the failure was noticed by the creation time of the issue
        cut.onFailure(api, report);

        assertThat(api.writes).isEmpty();
    }

    @Test
    void changedCauseIsCommented() throws IOException {
        cut.onFailure(api, report(T0, "boom"));

        cut.onFailure(api, report(T0.plusSeconds(60), "something completely different"));

        assertThat(api.get(1).comments).hasSize(1);
        assertThat(api.get(1).comments.getFirst()).contains("cause of the failure changed");
        assertThat(api.get(1).body).contains("something completely different");
    }

    @Test
    void successClosesTheIssueAndNextFailureOpensANewOne() throws IOException {
        cut.onFailure(api, report(T0, "boom"));

        var action = cut.onSuccess(api, REPO, Optional.of("abcdef123456"), T0.plusSeconds(60));

        assertThat(action).isEqualTo(Action.CLOSED);
        var first = api.get(1);
        assertThat(first.open).isFalse();
        assertThat(first.closedAsCompleted).isTrue();
        assertThat(first.comments).singleElement().asString().contains("succeeded again");
        assertThat(first.body).contains("Resolved on");
        assertThat(StatusMarker.parse(first.body)).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.RESOLVED));

        var next = cut.onFailure(api, report(T0.plusSeconds(120), "boom again"));

        assertThat(next).isEqualTo(Action.CREATED);
        assertThat(api.issues).hasSize(2);
        assertThat(api.openCount()).isEqualTo(1);
    }

    @Test
    void successWithoutIssueDoesNothing() throws IOException {
        var action = cut.onSuccess(api, REPO, Optional.empty(), T0);

        assertThat(action).isEqualTo(Action.NOTHING);
        assertThat(api.writes).isEmpty();
    }

    @Test
    void closingManuallyMutesUntilTheNextSuccess() throws IOException {
        cut.onFailure(api, report(T0, "boom"));
        api.closeByPerson(1);

        var muted = cut.onFailure(api, report(T0.plusSeconds(60), "boom"));

        assertThat(muted).isEqualTo(Action.MUTED);
        assertThat(api.issues).hasSize(1);
        assertThat(api.openCount()).isZero();

        var unmuted = cut.onSuccess(api, REPO, Optional.empty(), T0.plusSeconds(120));

        assertThat(unmuted).isEqualTo(Action.UNMUTED);
        assertThat(StatusMarker.parse(api.get(1).body)).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.RESOLVED));

        var next = cut.onFailure(api, report(T0.plusSeconds(180), "boom"));

        assertThat(next).isEqualTo(Action.CREATED);
        assertThat(api.openCount()).isEqualTo(1);
    }

    @Test
    void successAfterMuteWithoutFurtherUnmuteDoesNotTouchAnythingTwice() throws IOException {
        cut.onFailure(api, report(T0, "boom"));
        api.closeByPerson(1);
        cut.onSuccess(api, REPO, Optional.empty(), T0.plusSeconds(120));
        api.writes.clear();

        assertThat(cut.onSuccess(api, REPO, Optional.empty(), T0.plusSeconds(240))).isEqualTo(Action.NOTHING);
        assertThat(api.writes).isEmpty();
    }

    @Test
    void duplicatesAreClosedAndOnlyTheOldestIsKept() throws IOException {
        // two analyses racing each other created an issue each
        api.create("a", StatusReportRenderer.issueBody(report(T0, "boom"), T0, java.util.List.of()), java.util.List.of());
        api.create("b", StatusReportRenderer.issueBody(report(T0, "boom"), T0, java.util.List.of()), java.util.List.of());
        api.writes.clear();

        var action = cut.onFailure(api, report(T0.plusSeconds(60), "boom"));

        assertThat(action).isEqualTo(Action.UPDATED);
        assertThat(api.get(1).open).isTrue();
        assertThat(api.get(2).open).isFalse();
        assertThat(api.get(2).closedAsCompleted).isFalse();
        assertThat(api.get(2).comments).singleElement().asString().contains("duplicate of #1");
        // closing a duplicate must not look like a person muted it
        assertThat(StatusMarker.parse(api.get(2).body)).hasValueSatisfying(m -> assertThat(m.state()).isEqualTo(StatusMarker.State.RESOLVED));
        assertThat(api.openCount()).isEqualTo(1);
    }

    @Test
    void successClosesAllOpenIssues() throws IOException {
        api.create("a", StatusReportRenderer.issueBody(report(T0, "boom"), T0, java.util.List.of()), java.util.List.of());
        api.create("b", StatusReportRenderer.issueBody(report(T0, "boom"), T0, java.util.List.of()), java.util.List.of());

        assertThat(cut.onSuccess(api, REPO, Optional.empty(), T0.plusSeconds(60))).isEqualTo(Action.CLOSED);

        assertThat(api.openCount()).isZero();
    }

    @Test
    void resultOfAnOlderAnalysisIsIgnored() throws IOException {
        cut.onFailure(api, report(T0.plusSeconds(600), "newer"));
        api.writes.clear();

        assertThat(cut.onFailure(api, report(T0, "older"))).isEqualTo(Action.OUTDATED);
        assertThat(cut.onSuccess(api, REPO, Optional.empty(), T0)).isEqualTo(Action.OUTDATED);

        assertThat(api.writes).isEmpty();
        assertThat(api.get(1).open).isTrue();
    }

    @Test
    void issuesDisabledInRepositorySettingsSkipsEverything() throws IOException {
        api.issuesEnabled = false;

        assertThat(cut.onFailure(api, report(T0, "boom"))).isEqualTo(Action.ISSUES_DISABLED);
        assertThat(cut.onSuccess(api, REPO, Optional.empty(), T0)).isEqualTo(Action.ISSUES_DISABLED);

        assertThat(api.issues).isEmpty();
        assertThat(api.writes).isEmpty();
    }

    @Test
    void issuesDisabledDetectedByGitHubError() throws IOException {
        api.failNextWrite = new HttpException("Issues are disabled for this repo", 410, "Gone", "https://api.github.com/repos/acme/shop/issues");

        assertThat(cut.onFailure(api, report(T0, "boom"))).isEqualTo(Action.ISSUES_DISABLED);

        // remembered, so no further requests are made for a while
        api.writes.clear();
        assertThat(cut.onFailure(api, report(T0, "boom"))).isEqualTo(Action.ISSUES_DISABLED);
        assertThat(api.issues).isEmpty();

        // and tried again later
        clock.advance(StatusIssueService.ISSUES_DISABLED_MEMORY.plusMinutes(1));
        assertThat(cut.onFailure(api, report(T0, "boom"))).isEqualTo(Action.CREATED);
    }

    @Test
    void otherGitHubErrorsArePropagated() {
        api.failNextWrite = new IOException("server on fire");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cut.onFailure(api, report(T0, "boom")))
            .isInstanceOf(IOException.class).hasMessage("server on fire");
    }

    @Test
    void dryRunDoesNotWrite() throws IOException {
        var dryRun = new DryRunIssueApi(api, REPO);

        assertThat(cut.onFailure(dryRun, report(T0, "boom"))).isEqualTo(Action.CREATED);

        assertThat(api.issues).isEmpty();
        assertThat(api.writes).isEmpty();
    }

    @Test
    void noCodeOwnersMeansNoAssignee() throws IOException {
        api.codeOwners = null;

        cut.onFailure(api, report(T0, "boom"));

        assertThat(api.get(1).assignees).isEmpty();
        assertThat(api.get(1).body).doesNotContain("cc @");
    }

    @Test
    void unrelatedIssuesAreNotTouched() throws IOException {
        var foreign = new FakeIssueApi.Issue();
        foreign.number = 99;
        foreign.body = "someone's own issue";
        foreign.createdAt = T0;
        api.issues.add(foreign);

        cut.onFailure(api, report(T0, "boom"));
        cut.onSuccess(api, REPO, Optional.empty(), T0.plusSeconds(60));

        assertThat(foreign.open).isTrue();
        assertThat(foreign.comments).isEmpty();
    }
}
