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

    private final Clock clock;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
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
        var repo = report.repoFullName();
        synchronized (lockFor(repo)) {
            if (skipBecauseIssuesDisabled(api, repo)) {
                return Action.ISSUES_DISABLED;
            }
            try {
                return reportFailure(api, report);
            } catch (IOException e) {
                if (indicatesDisabledIssues(e)) {
                    rememberIssuesDisabled(repo);
                    return Action.ISSUES_DISABLED;
                }
                throw e;
            }
        }
    }

    /**
     * Closes the issue of the failure, if there is one.
     */
    public Action onSuccess(IssueApi api, String repo, java.util.Optional<String> commitSha, Instant started) throws IOException {
        synchronized (lockFor(repo)) {
            if (skipBecauseIssuesDisabled(api, repo)) {
                return Action.ISSUES_DISABLED;
            }
            try {
                return reportSuccess(api, repo, commitSha, started);
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
        var open = issues.stream().filter(IssueApi.StatusIssue::open)
            .sorted(Comparator.comparing(IssueApi.StatusIssue::createdAt).thenComparingInt(IssueApi.StatusIssue::number))
            .toList();
        var owners = CodeOwners.parse(api.readCodeOwners());

        if (open.isEmpty()) {
            var lastClosed = issues.stream().filter(i -> !i.open()).findFirst();
            if (lastClosed.isPresent() && isClosedByPerson(lastClosed.get())) {
                Log.infof("Status issue #%s of %s was closed manually, not reporting before the next successful analysis",
                    lastClosed.get().number(), report.repoFullName());
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

    private Action reportSuccess(IssueApi api, String repo, java.util.Optional<String> commitSha, Instant started) throws IOException {
        var issues = api.findStatusIssues();
        var open = issues.stream().filter(IssueApi.StatusIssue::open)
            .sorted(Comparator.comparing(IssueApi.StatusIssue::createdAt).thenComparingInt(IssueApi.StatusIssue::number))
            .toList();

        if (open.isEmpty()) {
            var lastClosed = issues.stream().filter(i -> !i.open()).findFirst();
            if (lastClosed.isPresent() && isClosedByPerson(lastClosed.get())) {
                api.updateBody(lastClosed.get().number(), StatusReportRenderer.markResolved(lastClosed.get().body()));
                Log.infof("Analysis of %s succeeded again, status issue #%s closed manually before is open to reports again", repo, lastClosed.get().number());
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

    private static void closeDuplicates(IssueApi api, String repo, IssueApi.StatusIssue keep, List<IssueApi.StatusIssue> duplicates) throws IOException {
        for (var duplicate : duplicates) {
            api.updateBody(duplicate.number(), StatusReportRenderer.markResolved(duplicate.body()));
            api.comment(duplicate.number(), "Closing as duplicate of #" + keep.number() + ", only one status issue is kept open.");
            api.close(duplicate.number(), false);
            Log.infof("Closed duplicate status issue #%s of %s", duplicate.number(), repo);
        }
    }

    /**
     * Technolinator marks the issues it closes as resolved, so anything else got closed by a person.
     */
    private static boolean isClosedByPerson(IssueApi.StatusIssue issue) {
        return StatusMarker.parse(issue.body()).map(m -> m.state() != StatusMarker.State.RESOLVED).orElse(true);
    }

    private boolean skipBecauseIssuesDisabled(IssueApi api, String repo) {
        var until = issuesDisabledUntil.get(repo);
        if (until != null && clock.instant().isBefore(until)) {
            return true;
        }
        if (!api.issuesEnabled()) {
            Log.infof("Issues are disabled in %s, skipping status issue", repo);
            rememberIssuesDisabled(repo);
            return true;
        }
        issuesDisabledUntil.remove(repo);
        return false;
    }

    private void rememberIssuesDisabled(String repo) {
        issuesDisabledUntil.put(repo, clock.instant().plus(ISSUES_DISABLED_MEMORY));
    }

    static boolean indicatesDisabledIssues(IOException e) {
        if (e instanceof HttpException http && http.getResponseCode() == 410) {
            return true;
        }
        var message = e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("issues are disabled");
    }

    private Object lockFor(String repo) {
        return locks.computeIfAbsent(repo, k -> new Object());
    }
}
