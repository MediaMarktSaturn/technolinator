package com.mediamarktsaturn.technolinator.report;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns {@link FailureReport}s into the markdown shown in status issues and pull-request comments.
 */
public final class StatusReportRenderer {

    /**
     * GitHub rejects texts longer than 65536 characters, stay well below.
     */
    public static final int MAX_BODY_LENGTH = 60_000;

    static final int MAX_EXCERPT_LENGTH = 30_000;
    static final int MIN_EXCERPT_LENGTH = 2_000;
    static final int MAX_SHOWN_FAILURES = 5;

    private static final String TICK = "\u0060";
    private static final Pattern BACKTICK_RUN = Pattern.compile("\u0060+");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private StatusReportRenderer() {
        // utility
    }

    // ---- status issue ----

    public static String issueTitle(String branch) {
        return "Technolinator: SBOM creation fails on " + branch;
    }

    /**
     * @param failingSince the time the issue got created, which is the first known failure
     * @param ccTeams      owners of the repository which can't be assigned to an issue, but shall get notified
     */
    public static String issueBody(FailureReport report, Instant failingSince, List<String> ccTeams) {
        var marker = new StatusMarker(StatusMarker.State.FAILED, report.started(), report.commitSha().orElse("-"), report.errorHash());
        var body = new StringBuilder();
        body.append(marker.render()).append("\n\n");
        body.append("## ❌ SBOM creation is failing on ").append(code(report.branch())).append("\n\n");
        body.append("Technolinator could not create the SBOM of this repository, so its dependencies are not tracked in Dependency-Track. ")
            .append("This issue shows the current state of the analysis of ").append(code(report.branch())).append(".\n\n");
        body.append("| | |\n|---|---|\n");
        body.append("| Failing since | ").append(TIME.format(failingSince)).append(" |\n");
        body.append("| Last analysis | ").append(commitLink(report)).append(" · ").append(TIME.format(report.started())).append(" |\n");
        if (report.traceId() != null && !report.traceId().isBlank()) {
            body.append("| Trace ID | ").append(code(report.traceId())).append(" |\n");
        }
        body.append("\n");

        var footer = new StringBuilder();
        footer.append("\n---\n");
        if (!ccTeams.isEmpty()) {
            footer.append("\ncc ").append(String.join(" ", ccTeams)).append("\n");
        }
        footer.append("\n<sub>Maintained by Technolinator: updated with every failing analysis of ").append(code(report.branch()))
            .append(" and closed automatically after the next successful one. ")
            .append("If you close it manually, Technolinator stays quiet until the next successful analysis. ")
            .append("To switch these issues off, set ").append(code("reporting.issues: false"))
            .append(" in ").append(code(".github/technolinator.yml")).append(".</sub>\n");

        var budget = MAX_BODY_LENGTH - body.length() - footer.length();
        body.append(failureDetails(report, true, "###", budget));
        body.append(footer);
        return body.toString();
    }

    public static String issueCauseChangedComment(FailureReport report) {
        var summaries = report.failures().stream()
            .map(f -> "- " + code(f.project()) + ": " + f.summary())
            .reduce((a, b) -> a + "\n" + b).orElse("");
        return "The cause of the failure changed with commit " + commitLink(report) + ":\n\n" + summaries
            + "\n\nSee the description for details.";
    }

    public static String issueResolvedComment(Optional<String> commitSha) {
        return "✅ SBOM creation succeeded again" + commitSha.map(sha -> " for commit " + code(shortSha(sha))).orElse("")
            + ". Closing this issue, a new one is opened if it fails again.";
    }

    /**
     * @return the given issue body, now marked as resolved and with a note on top of the failure details
     */
    public static String resolvedIssueBody(String body, Instant resolvedAt, Optional<String> commitSha) {
        var marker = StatusMarker.parse(body);
        var resolvedMarker = new StatusMarker(StatusMarker.State.RESOLVED,
            marker.map(StatusMarker::started).orElse(resolvedAt), marker.map(StatusMarker::sha).orElse("-"), marker.map(StatusMarker::err).orElse("-"));
        var withMarker = marker.isPresent() ? StatusMarker.replace(body, resolvedMarker) : body;
        var banner = "> ✅ **Resolved on " + TIME.format(resolvedAt) + "**" + commitSha.map(sha -> " with commit " + code(shortSha(sha))).orElse("")
            + ". The failure below is kept for reference.\n\n";
        var markerEnd = withMarker.indexOf("-->");
        if (markerEnd < 0 || withMarker.contains("**Resolved on")) {
            return withMarker;
        }
        var insertAt = markerEnd + "-->".length();
        return withMarker.substring(0, insertAt) + "\n\n" + banner.stripTrailing() + withMarker.substring(insertAt);
    }

    /**
     * @return the given issue body, marked as resolved without any visible change
     */
    public static String markResolved(String body) {
        var marker = StatusMarker.parse(body);
        if (marker.isEmpty()) {
            return body;
        }
        var m = marker.get();
        return StatusMarker.replace(body, new StatusMarker(StatusMarker.State.RESOLVED, m.started(), m.sha(), m.err()));
    }

    // ---- pull-request comment ----

    /**
     * @param includeLogs     whether logs of the analysed code are shown, which is questionable for untrusted code of forks
     * @param otherContent    further content in the comment, which also needs to fit in
     * @param commentMarker   the invisible marker identifying comments of Technolinator, goes to the end
     */
    public static String pullRequestFailureComment(FailureReport report, boolean includeLogs, String otherContent, String commentMarker) {
        var marker = new StatusMarker(StatusMarker.State.FAILED, report.started(), report.commitSha().orElse("-"), report.errorHash());
        var body = new StringBuilder();
        body.append(marker.render()).append("\n\n");
        body.append("## ❌ Technolinator could not create the SBOM\n\n");
        body.append("The analysis of ").append(commitLink(report)).append(" failed (").append(TIME.format(report.started())).append("). ")
            .append("This comment is updated with every analysis of this pull request.\n\n");

        var footer = new StringBuilder();
        footer.append("\n");
        if (otherContent != null && !otherContent.isBlank()) {
            footer.append("\n---\n\n").append(otherContent.strip()).append("\n");
        }
        footer.append("\n").append(commentMarker);

        var budget = Math.max(MIN_EXCERPT_LENGTH * 2, MAX_BODY_LENGTH - body.length() - footer.length());
        body.append(failureDetails(report, includeLogs, "###", budget));
        body.append(footer);
        return body.toString();
    }

    public static String pullRequestRecoveredComment(Instant started, Optional<String> commitSha, String otherContent, String commentMarker) {
        var marker = new StatusMarker(StatusMarker.State.RECOVERED, started, commitSha.orElse("-"), "-");
        var body = new StringBuilder();
        body.append(marker.render()).append("\n\n");
        body.append("## 🎉 Technolinator can analyse this pull request again\n\n");
        body.append("Great, the SBOM")
            .append(commitSha.map(sha -> " of commit " + code(shortSha(sha))).orElse(""))
            .append(" was created successfully, the earlier failure is fixed. Thanks for taking care! 🚀\n");
        if (otherContent != null && !otherContent.isBlank()) {
            body.append("\n---\n\n").append(otherContent.strip()).append("\n");
        }
        body.append("\n").append(commentMarker);
        return body.toString();
    }

    // ---- shared ----

    static String failureDetails(FailureReport report, boolean includeLogs, String headingLevel, int budget) {
        var failures = report.failures();
        var shown = failures.subList(0, Math.min(MAX_SHOWN_FAILURES, failures.size()));
        var perFailure = Math.max(MIN_EXCERPT_LENGTH, Math.min(MAX_EXCERPT_LENGTH, budget / Math.max(1, shown.size()) - 1_500));

        var result = new StringBuilder();
        for (var failure : shown) {
            result.append(headingLevel).append(' ').append(code(failure.project()));
            if (failure.tool() != null && !failure.tool().isBlank()) {
                result.append(" – probably ").append(code(failure.tool()));
            }
            result.append("\n\n");
            result.append("**What happened:** ").append(failure.summary()).append("\n\n");
            if (includeLogs && failure.errorLine() != null && !failure.errorLine().isBlank()) {
                result.append("**Key error:** ").append(code(failure.errorLine())).append("\n\n");
            }
            if (failure.exitCode() != null) {
                result.append("**Exit code:** ").append(code(String.valueOf(failure.exitCode()))).append("\n\n");
            }
            if (failure.command() != null && !failure.command().isBlank()) {
                result.append("**Command:** ").append(code(failure.command())).append("\n\n");
            }
            if (failure.hint() != null && !failure.hint().isBlank()) {
                result.append("**How to fix:** ").append(failure.hint()).append("\n\n");
            }
            if (!includeLogs) {
                result.append("_Logs are not shown for pull requests from forks. They are available in the analysis of the base repository._\n\n");
            } else if (failure.excerpt() != null && !failure.excerpt().isBlank()) {
                var excerpt = LogExcerpter.fit(failure.excerpt(), perFailure);
                result.append("<details>\n<summary>Log excerpt</summary>\n\n")
                    .append(fenced(excerpt)).append("\n\n</details>\n\n");
            }
        }
        if (failures.size() > shown.size()) {
            result.append("_").append(failures.size() - shown.size()).append(" more failing projects not shown: ")
                .append(failures.subList(shown.size(), failures.size()).stream().map(f -> code(f.project()))
                    .reduce((a, b) -> a + ", " + b).orElse(""))
                .append("_\n\n");
        }
        return result.toString();
    }

    /**
     * A code block which can't be broken out of by the content.
     */
    static String fenced(String content) {
        var fence = "`".repeat(Math.max(3, longestBacktickRun(content) + 1));
        return fence + "\n" + content + "\n" + fence;
    }

    /**
     * Inline code which can't be broken out of by the content.
     */
    static String code(String content) {
        var single = content.replace("\n", " ").strip();
        var run = longestBacktickRun(single);
        if (run == 0) {
            return TICK + single + TICK;
        }
        var delimiter = TICK.repeat(run + 1);
        return delimiter + " " + single + " " + delimiter;
    }

    private static int longestBacktickRun(String content) {
        var longest = 0;
        var matcher = BACKTICK_RUN.matcher(content);
        while (matcher.find()) {
            longest = Math.max(longest, matcher.end() - matcher.start());
        }
        return longest;
    }

    private static String commitLink(FailureReport report) {
        return report.commitSha().map(sha -> {
            var shortSha = code(shortSha(sha));
            if (report.repoHtmlUrl() == null || report.repoHtmlUrl().isBlank()) {
                return shortSha;
            }
            return "[" + shortSha + "](" + report.repoHtmlUrl() + "/commit/" + sha + ")";
        }).orElse("the latest commit");
    }

    static String shortSha(String sha) {
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }
}
