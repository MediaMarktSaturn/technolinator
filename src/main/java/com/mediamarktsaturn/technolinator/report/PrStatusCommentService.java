package com.mediamarktsaturn.technolinator.report;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Maintains the single comment of Technolinator in a pull-request.
 * It contains the vulnerability report, and the failure of the analysis if there is any.
 * <ul>
 *   <li>A failing analysis shows its cause in the comment, together with the report of the projects which succeeded.</li>
 *   <li>The comment is updated with every analysis, there is never more than one.</li>
 *   <li>A succeeding analysis after a failing one congratulates in the comment, which stays.</li>
 *   <li>Without any failure involved, it works as before: the vulnerability report is the comment.</li>
 * </ul>
 */
@ApplicationScoped
public class PrStatusCommentService {

    /**
     * Identifies the comment of Technolinator, being the last line of it.
     */
    public static final String COMMENT_MARKER = "[//]: # (Technolinator)";

    private static final int LOCK_STRIPES = 64;

    public enum Outcome {
        FAILED,
        SUCCEEDED,
        /**
         * Neither a success nor a failure caused by the pull-request, e.g. the checkout failed.
         */
        UNKNOWN
    }

    public enum Action {
        CREATED,
        UPDATED,
        NOTHING,
        /**
         * A newer analysis was already reported.
         */
        OUTDATED
    }

    /**
     * @param key           identifies the pull-request
     * @param failure       what failed, required for {@link Outcome#FAILED} with {@code statusEnabled}
     * @param reportText    the vulnerability report, null if there is none
     * @param includeLogs   whether logs of the analysed code may be shown
     * @param statusEnabled whether failures are reported at all. Otherwise it works like before the failure reporting existed
     * @param botLogin      login of the GitHub app, duplicates are only deleted if they are written by a bot
     */
    public record Update(
        String key,
        Outcome outcome,
        FailureReport failure,
        String reportText,
        boolean includeLogs,
        boolean statusEnabled,
        Instant started,
        Optional<String> commitSha,
        Optional<String> botLogin
    ) {
    }

    private final Object[] locks = new Object[LOCK_STRIPES];

    public PrStatusCommentService() {
        for (var i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    public static boolean isStatusComment(String body) {
        return body != null && body.trim().endsWith(COMMENT_MARKER);
    }

    public Action update(CommentApi api, Update update) throws IOException {
        synchronized (locks[Math.floorMod(update.key().hashCode(), LOCK_STRIPES)]) {
            return doUpdate(api, update);
        }
    }

    private Action doUpdate(CommentApi api, Update u) throws IOException {
        var found = api.findComments(PrStatusCommentService::isStatusComment);
        // Anyone can write the marker into a comment, but only comments of the app can be updated, and be trusted.
        // Without status management the comment is found like it always was, whoever wrote it.
        var comments = u.statusEnabled() ? found.stream().filter(c -> isWrittenByApp(c, u)).toList() : found;
        var existing = comments.isEmpty() ? null : comments.getFirst();
        if (u.statusEnabled() && comments.size() > 1) {
            deleteDuplicates(api, u, comments.subList(1, comments.size()));
        }

        var marker = existing == null ? Optional.<StatusMarker>empty() : StatusMarker.parse(existing.body());
        if (u.statusEnabled() && marker.map(m -> m.started().isAfter(u.started())).orElse(false)) {
            Log.infof("Comment in %s already reflects a newer analysis, ignoring this result", u.key());
            return Action.OUTDATED;
        }

        var text = textFor(u, marker);
        if (text == null) {
            return Action.NOTHING;
        }
        if (existing == null) {
            api.create(text);
            return Action.CREATED;
        }
        api.update(existing.id(), text);
        return Action.UPDATED;
    }

    /**
     * @return the new content of the comment, or null if it should stay as it is
     */
    private static String textFor(Update u, Optional<StatusMarker> existing) {
        var failedBefore = u.statusEnabled() && existing.map(m -> m.state() == StatusMarker.State.FAILED).orElse(false);
        return switch (u.outcome()) {
            case FAILED -> {
                if (u.statusEnabled() && u.failure() != null) {
                    yield StatusReportRenderer.pullRequestFailureComment(u.failure(), u.includeLogs(), u.reportText(), COMMENT_MARKER);
                }
                yield plainReport(u.reportText());
            }
            case SUCCEEDED -> failedBefore
                ? StatusReportRenderer.pullRequestRecoveredComment(u.started(), u.commitSha(), u.reportText(), COMMENT_MARKER)
                : plainReport(u.reportText());
            // do not hide a failure because of an analysis which did not tell anything about it
            case UNKNOWN -> failedBefore ? null : plainReport(u.reportText());
        };
    }

    private static String plainReport(String reportText) {
        return reportText == null ? null : "%s\n\n%s".formatted(reportText, COMMENT_MARKER);
    }

    private static boolean isWrittenByApp(CommentApi.Comment comment, Update u) {
        return BotIdentity.isWrittenByApp(comment.authorLogin(), u.botLogin());
    }

    private static void deleteDuplicates(CommentApi api, Update u, java.util.List<CommentApi.Comment> duplicates) {
        for (var duplicate : duplicates) {
            try {
                api.delete(duplicate.id());
            } catch (IOException e) {
                Log.warnf(e, "Could not delete duplicate comment in %s", u.key());
            }
        }
    }
}
