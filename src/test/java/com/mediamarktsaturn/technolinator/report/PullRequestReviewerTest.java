package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReview;
import org.kohsuke.github.GHPullRequestReviewBuilder;
import org.kohsuke.github.GHPullRequestReviewEvent;
import org.kohsuke.github.GHPullRequestReviewState;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullRequestReviewerTest {

    private static final String BOT = "technolinator[bot]";
    private static final VulnerabilitySummary FINDINGS = new VulnerabilitySummary(0, 2, 3, 4, 0);

    private final GHPullRequest pullRequest = mock(GHPullRequest.class);
    private final GHPullRequestReviewBuilder builder = mock(GHPullRequestReviewBuilder.class, Mockito.RETURNS_SELF);
    private PullRequestReviewer reviewer;

    @BeforeEach
    void setUp() throws IOException {
        var bot = mock(BotIdentity.class);
        when(bot.login()).thenReturn(Optional.of(BOT));
        reviewer = new PullRequestReviewer(bot);
        when(pullRequest.createReview()).thenReturn(builder);
        when(pullRequest.getHtmlUrl()).thenReturn(java.net.URI.create("https://github.com/acme/shop/pull/1").toURL());
        reviews();
    }

    @SuppressWarnings("unchecked")
    private void reviews(GHPullRequestReview... reviews) {
        var iterable = mock(PagedIterable.class);
        var iterator = mock(PagedIterator.class);
        when(pullRequest.listReviews()).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(iterator);
        var remaining = new ArrayDeque<>(Arrays.asList(reviews));
        when(iterator.hasNext()).thenAnswer(invocation -> !remaining.isEmpty());
        when(iterator.next()).thenAnswer(invocation -> remaining.poll());
    }

    private static GHPullRequestReview review(GHPullRequestReviewState state, String body, String author) throws IOException {
        var review = mock(GHPullRequestReview.class);
        var user = mock(GHUser.class);
        when(user.getLogin()).thenReturn(author);
        when(review.getUser()).thenReturn(user);
        when(review.getState()).thenReturn(state);
        when(review.getBody()).thenReturn(body);
        return review;
    }

    private static GHPullRequestReview ownReview(VulnerabilitySummary summary) throws IOException {
        return review(GHPullRequestReviewState.CHANGES_REQUESTED, PullRequestReviewer.body(summary, Severity.HIGH), BOT);
    }

    @Test
    void requestsChangesForVulnerabilitiesAtTheThreshold() throws IOException {
        var action = reviewer.review(pullRequest, "c0ffee", FINDINGS, Severity.HIGH);

        assertThat(action).isEqualTo(PullRequestReviewer.Action.REQUESTED_CHANGES);
        verify(builder).body(PullRequestReviewer.body(FINDINGS, Severity.HIGH));
        verify(builder).event(GHPullRequestReviewEvent.REQUEST_CHANGES);
        verify(builder).commitId("c0ffee");
        verify(builder).create();
        assertThat(PullRequestReviewer.body(FINDINGS, Severity.HIGH))
            .contains("severity high or above: 0 critical, 2 high, 3 medium, 4 low")
            .endsWith(PullRequestReviewer.MARKER);
    }

    @Test
    void requestsChangesForVulnerabilitiesAboveTheThreshold() throws IOException {
        var action = reviewer.review(pullRequest, null, new VulnerabilitySummary(1, 0, 0, 0, 0), Severity.MEDIUM);

        assertThat(action).isEqualTo(PullRequestReviewer.Action.REQUESTED_CHANGES);
        verify(builder, never()).commitId(anyString());
    }

    @Test
    void doesNothingWithoutVulnerabilitiesAtTheThreshold() throws IOException {
        var action = reviewer.review(pullRequest, "c0ffee", new VulnerabilitySummary(0, 0, 3, 4, 5), Severity.HIGH);

        assertThat(action).isEqualTo(PullRequestReviewer.Action.NONE);
        verify(pullRequest, never()).createReview();
    }

    @Test
    void keepsTheReviewTellingTheSameNumbers() throws IOException {
        var own = ownReview(FINDINGS);
        reviews(own);

        var action = reviewer.review(pullRequest, "c0ffee", FINDINGS, Severity.HIGH);

        assertThat(action).isEqualTo(PullRequestReviewer.Action.KEPT);
        verify(own, never()).dismiss(anyString());
        verify(pullRequest, never()).createReview();
    }

    @Test
    void replacesTheReviewTellingOtherNumbers() throws IOException {
        var own = ownReview(new VulnerabilitySummary(0, 1, 0, 0, 0));
        reviews(own);

        var action = reviewer.review(pullRequest, "c0ffee", FINDINGS, Severity.HIGH);

        assertThat(action).isEqualTo(PullRequestReviewer.Action.REQUESTED_CHANGES);
        verify(own).dismiss(anyString());
        verify(builder).create();
    }

    @Test
    void dismissesTheReviewOnceTheVulnerabilitiesAreGone() throws IOException {
        var own = ownReview(FINDINGS);
        reviews(own);

        var action = reviewer.review(pullRequest, "c0ffee", VulnerabilitySummary.NONE, Severity.HIGH);

        assertThat(action).isEqualTo(PullRequestReviewer.Action.DISMISSED);
        verify(own).dismiss(any());
        verify(pullRequest, never()).createReview();
    }

    @Test
    void leavesForeignAndOtherReviewsAlone() throws IOException {
        var body = PullRequestReviewer.body(FINDINGS, Severity.HIGH);
        var byHuman = review(GHPullRequestReviewState.CHANGES_REQUESTED, body, "alice");
        var approvedByApp = review(GHPullRequestReviewState.APPROVED, body, BOT);
        var dismissedOne = review(GHPullRequestReviewState.DISMISSED, body, BOT);
        var otherOfApp = review(GHPullRequestReviewState.CHANGES_REQUESTED, "please fix", BOT);
        var withoutBody = review(GHPullRequestReviewState.CHANGES_REQUESTED, null, BOT);
        reviews(byHuman, approvedByApp, dismissedOne, otherOfApp, withoutBody);

        var action = reviewer.review(pullRequest, "c0ffee", VulnerabilitySummary.NONE, Severity.HIGH);

        assertThat(action).isEqualTo(PullRequestReviewer.Action.NONE);
        for (var review : new GHPullRequestReview[]{byHuman, approvedByApp, dismissedOne, otherOfApp, withoutBody}) {
            verify(review, never()).dismiss(any());
        }
    }
}
