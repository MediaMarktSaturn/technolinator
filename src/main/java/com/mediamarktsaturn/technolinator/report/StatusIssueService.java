package com.mediamarktsaturn.technolinator.report;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.kohsuke.github.HttpException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains the one issue per repository, which shows that the analysis of the default branch fails.
 * <ul>
 *   <li>A failing analysis creates the issue, or updates the description of the open one.</li>
 *   <li>A succeeding analysis closes it.</li>
 *   <li>At most one issue is open. Duplicates, e.g. created by analyses running concurrently, are closed.</li>
 *   <li>Closing the issue manually mutes it, until the next analysis succeeds.</li>
 * </ul>
 * All operations are idempotent, as there is no reliable way to prevent concurrent runs of several instances.
 * Everything needed is found in the issues themselves, see {@link StatusMarker}, nothing is stored in Technolinator.
 */
@ApplicationScoped
public class StatusIssueService {

    public enum Action {
        CREATED,
        UPDATED,
        CLOSED,
        /**
         * An issue closed by a person is open to reports again.
         */
        UNMUTED,
        /**
         * A person closed the issue after the last failure, so nothing is reported until the next success.
         */
        MUTED,
        /**
         * A newer analysis was already reported.
         */
        OUTDATED,
        NOTHING,
        ISSUES_DISABLED
    }

    static final Duration ISSUES_DISABLED_MEMORY = Duration.ofHours(6);

    private static final int LOCK_STRIPES = 256;

    private interface Operation {
        Action run() throws IOException;
    }

    private final Clock clock;
    private final KeyedLocks locks = new KeyedLocks(LOCK_STRIPES);
    private final ConcurrentHashMap<String, Instant> issuesDisabledUntil = new ConcurrentHashMap<>();

    public StatusIssueService() {
        this(Clock.systemUTC());
    }

    StatusIssueService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Makes sure an open issue tells about the failure.
     */
    public Action onFailure(IssueApi api, FailureReport report) throws IOException {
        return synchronizedOn(api, report.repoFullName(), () -> reportFailure(api, report));
    }

    /**
     * Closes the issue of the failure, if there is one.
     */
    public Action onSuccess(IssueApi api, String repo, Optional<String> commitSha, Instant started) throws IOException {
        return synchronizedOn(api, repo, () -> reportSuccess(api, repo, commitSha, started));
    }

    private Action synchronizedOn(IssueApi api, String repo, Operation operation) throws IOException {
        synchronized (locks.of(repo)) {
            if (skipBecauseIssuesDisabled(api, repo)) {
                return Action.ISSUES_DISABLED;
            }
            try {
                return operation.run();
            } catch (IOException e) {
                if (indicatesDisabledIssues(e)) {
                    rememberIssuesDisabled(repo);
                    return Action.ISSUES_DISABLED;
                }
                throw e;
            }
        }
    }

    private Action reportFailure(IssueApi api, FailureReport report) throws IOException {
        var issues = api.findStatusIssues();
        var open = openIssues(issues);
        var owners = CodeOwners.parse(api.readCodeOwners());

        if (open.isEmpty()) {
            var closedByPerson = lastClosedByPerson(issues);
            if (closedByPerson.isPresent()) {
                Log.infof("Status issue #%s of %s was closed manually, not reporting before the next successful analysis",
                    closedByPerson.get().number(), report.repoFullName());
                return Action.MUTED;
            }
            var created = api.create(
                StatusReportRenderer.issueTitle(report.branch()),
                StatusReportRenderer.issueBody(report, report.started(), owners.teams()),
                owners.users()
            );
            Log.infof("Created status issue #%s in %s", created.number(), report.repoFullName());
            return Action.CREATED;
        }

        var keep = open.getFirst();
        closeDuplicates(api, report.repoFullName(), keep, open.subList(1, open.size()));

        var marker = StatusMarker.parse(keep.body());
        if (marker.map(m -> m.started().isAfter(report.started())).orElse(false)) {
            Log.infof("Status issue #%s of %s already reflects a newer analysis, ignoring this result", keep.number(), report.repoFullName());
            return Action.OUTDATED;
        }

        var body = StatusReportRenderer.issueBody(report, keep.createdAt(), owners.teams());
        if (!body.equals(keep.body())) {
            api.updateBody(keep.number(), body);
        }
        var causeChanged = marker.map(m -> !m.err().equals(report.errorHash())).orElse(false);
        if (causeChanged) {
            api.comment(keep.number(), StatusReportRenderer.issueCauseChangedComment(report));
        }
        Log.infof("Updated status issue #%s of %s", keep.number(), report.repoFullName());
        return Action.UPDATED;
    }

    private Action reportSuccess(IssueApi api, String repo, Optional<String> commitSha, Instant started) throws IOException {
        var issues = api.findStatusIssues();
        var open = openIssues(issues);

        if (open.isEmpty()) {
            var closedByPerson = lastClosedByPerson(issues);
            if (closedByPerson.isPresent()) {
                var issue = closedByPerson.get();
                api.updateBody(issue.number(), StatusReportRenderer.markResolved(issue.body()));
                Log.infof("Analysis of %s succeeded again, status issue #%s closed manually before is open to reports again", repo, issue.number());
                return Action.UNMUTED;
            }
            return Action.NOTHING;
        }

        var newerFailureKnown = open.stream()
            .map(i -> StatusMarker.parse(i.body()))
            .anyMatch(m -> m.map(marker -> marker.started().isAfter(started)).orElse(false));
        if (newerFailureKnown) {
            Log.infof("Status issue of %s reflects a newer analysis than this successful one, keeping it open", repo);
            return Action.OUTDATED;
        }

        var now = clock.instant();
        for (var issue : open) {
            api.updateBody(issue.number(), StatusReportRenderer.resolvedIssueBody(issue.body(), now, commitSha));
            api.comment(issue.number(), StatusReportRenderer.issueResolvedComment(commitSha));
            api.close(issue.number(), true);
            Log.infof("Closed status issue #%s of %s", issue.number(), repo);
        }
        return Action.CLOSED;
    }

    /**
     * @return the open issues, the oldest first
     */
    private static List<IssueApi.StatusIssue> openIssues(List<IssueApi.StatusIssue> issues) {
        return issues.stream().filter(IssueApi.StatusIssue::open)
            .sorted(Comparator.comparing(IssueApi.StatusIssue::createdAt).thenComparingInt(IssueApi.StatusIssue::number))
            .toList();
    }

    /**
     * Technolinator marks the issues it closes as resolved, so anything else got closed by a person.
     *
     * @param issues the newest first
     */
    private static Optional<IssueApi.StatusIssue> lastClosedByPerson(List<IssueApi.StatusIssue> issues) {
        return issues.stream().filter(i -> !i.open()).findFirst()
            .filter(i -> StatusMarker.parse(i.body()).map(m -> m.state() != StatusMarker.State.RESOLVED).orElse(true));
    }

    private static void closeDuplicates(IssueApi api, String repo, IssueApi.StatusIssue keep, List<IssueApi.StatusIssue> duplicates) throws IOException {
        for (var duplicate : duplicates) {
            api.updateBody(duplicate.number(), StatusReportRenderer.markResolved(duplicate.body()));
            api.comment(duplicate.number(), "Closing as duplicate of #" + keep.number() + ", only one status issue is kept open.");
            api.close(duplicate.number(), false);
            Log.infof("Closed duplicate status issue #%s of %s", duplicate.number(), repo);
        }
    }

    private boolean skipBecauseIssuesDisabled(IssueApi api, String repo) {
        var until = issuesDisabledUntil.get(repo);
        if (until != null) {
            if (clock.instant().isBefore(until)) {
                return true;
            }
            issuesDisabledUntil.remove(repo);
        }
        if (!api.issuesEnabled()) {
            Log.infof("Issues are disabled in %s, skipping status issue", repo);
            return true;
        }
        return false;
    }

    private void rememberIssuesDisabled(String repo) {
        var now = clock.instant();
        issuesDisabledUntil.values().removeIf(until -> !now.isBefore(until));
        issuesDisabledUntil.put(repo, now.plus(ISSUES_DISABLED_MEMORY));
    }

    static boolean indicatesDisabledIssues(IOException e) {
        if (e instanceof HttpException http && http.getResponseCode() == 410) {
            return true;
        }
        var message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("issues are disabled");
    }
}
