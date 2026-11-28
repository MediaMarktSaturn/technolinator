package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReview;
import org.kohsuke.github.GHPullRequestReviewEvent;
import org.kohsuke.github.GHPullRequestReviewState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Requests changes of a pull-request, whose analysis found vulnerabilities at or above the threshold, by a review of the GitHub app.
 * <ul>
 *     <li>The review states the number of vulnerabilities per severity, and is pinned to the analysed commit.</li>
 *     <li>It is kept as long as the analysis states the same numbers, otherwise it is dismissed and replaced by a new one, which tells the new numbers.</li>
 *     <li>If the analysis doesn't find vulnerabilities at or above the threshold anymore, the review is dismissed, so it doesn't block the merge any longer.</li>
 * </ul>
 * Only reviews of the app, that requested changes and carry the {@link #MARKER}, are touched.
 */
@ApplicationScoped
public class PullRequestReviewer {

    /**
     * Identifies the reviews written by this class
     */
    static final String MARKER = "[//]: # (Technolinator review)";

    public enum Action {
        /**
         * A review requesting changes was written
         */
        REQUESTED_CHANGES,
        /**
         * The review requesting changes is up to date
         */
        KEPT,
        /**
         * The review requesting changes was dismissed, as the vulnerabilities are gone
         */
        DISMISSED,
        /**
         * There was nothing to do
         */
        NONE
    }

    private final BotIdentity botIdentity;

    public PullRequestReviewer(BotIdentity botIdentity) {
        this.botIdentity = botIdentity;
    }

    /**
     * Brings the reviews of the app in line with the [vulnerabilities] of the commit.
     * Performs blocking calls to GitHub.
     *
     * @param commitSha the analysed head commit of the pull-request, may be null
     * @param threshold the lowest severity requiring changes
     */
    public Action review(GHPullRequest pullRequest, String commitSha, VulnerabilitySummary vulnerabilities, VulnerabilitySummary.Severity threshold) throws IOException {
        var active = activeReviews(pullRequest);
        if (vulnerabilities.atOrAbove(threshold) > 0) {
            var body = body(vulnerabilities, threshold);
            if (active.size() == 1 && body.strip().equals(Optional.ofNullable(active.getFirst().getBody()).orElse("").strip())) {
                return Action.KEPT;
            }
            dismiss(active, "Superseded by the new analysis");
            var review = pullRequest.createReview().body(body).event(GHPullRequestReviewEvent.REQUEST_CHANGES);
            if (commitSha != null && !commitSha.isBlank()) {
                review.commitId(commitSha);
            }
            review.create();
            Log.infof("Requested changes of pull-request %s: %s", pullRequest.getHtmlUrl(), vulnerabilities.describe());
            return Action.REQUESTED_CHANGES;
        }
        if (active.isEmpty()) {
            return Action.NONE;
        }
        dismiss(active, "The analysis doesn't find vulnerabilities of severity %s or above anymore".formatted(threshold.label()));
        Log.infof("Dismissed the review of pull-request %s, as there are no vulnerabilities of severity %s or above anymore", pullRequest.getHtmlUrl(), threshold.label());
        return Action.DISMISSED;
    }

    static String body(VulnerabilitySummary vulnerabilities, VulnerabilitySummary.Severity threshold) {
        return """
            **Technolinator requests changes:** the analysis found vulnerabilities of severity %s or above: %s.

            The comment of Technolinator on this pull request has the details. This review is dismissed automatically, once the analysis doesn't find vulnerabilities of this severity anymore.

            %s""".formatted(threshold.label(), vulnerabilities.describe(), MARKER);
    }

    private static void dismiss(List<GHPullRequestReview> reviews, String message) throws IOException {
        for (var review : reviews) {
            review.dismiss(message);
        }
    }

    private List<GHPullRequestReview> activeReviews(GHPullRequest pullRequest) throws IOException {
        var app = botIdentity.login();
        var active = new ArrayList<GHPullRequestReview>();
        for (var review : pullRequest.listReviews()) {
            if (review.getState() == GHPullRequestReviewState.CHANGES_REQUESTED
                && review.getBody() != null && review.getBody().contains(MARKER)
                && BotIdentity.isWrittenByApp(review.getUser() == null ? null : review.getUser().getLogin(), app)) {
                active.add(review);
            }
        }
        return active;
    }
}
