package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.Commons;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns {@link FailureReport}s into the markdown shown in status issues and pull-request comments.
 */
public final class StatusReportRenderer {

    /**
     * GitHub rejects texts longer than 65536 characters, stay well below.
     */
    public static final int MAX_BODY_LENGTH = Commons.MAX_COMMENT_LENGTH;

    /**
     * Where the repository specific configuration is documented, for the people and agents who read failure reports.
     */
    public static final String CONFIG_DOCS_URL = "https://github.com/MediaMarktSaturn/technolinator/blob/main/docs/Repository_Config.md";

    static final int MAX_EXCERPT_LENGTH = 30_000;
    static final int MIN_EXCERPT_LENGTH = 2_000;
    static final int MAX_SHOWN_FAILURES = 5;
    static final int MAX_LISTED_PROJECTS = 10;
    static final int MAX_INLINE_LENGTH = 500;
    static final int MAX_TITLE_LENGTH = 256;
    /**
     * Space of a failure comment which is guaranteed for the other content, which is the vulnerability report.
     * It can use more, as far as the failure details leave space.
     */
    static final int MAX_OTHER_CONTENT_LENGTH = 25_000;
    /**
     * What the details of a failure need besides the excerpt: the project, the command, the error line and the explanations.
     */
    private static final int FAILURE_OVERHEAD = 2_500;

    private static final String TICK = "\u0060";
    private static final Pattern BACKTICK_RUN = Pattern.compile("\u0060+");
    private static final Pattern LINE_BREAKS = Pattern.compile("[\r\n]+");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private StatusReportRenderer() {
        // utility
    }

    // ---- status issue ----

    public static String issueTitle(String branch) {
        var title = "Technolinator: SBOM creation fails on " + branch;
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH - 1) + "…" : title;
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
        var scope = report.scope();
        if (scope.isPartial()) {
            body.append("Technolinator could not create the SBOM of ").append(scope.failed()).append(" of ").append(scope.total())
                .append(" projects of this repository, so their dependencies are not tracked in Dependency-Track. ")
                .append("This issue shows the current state of the analysis of ").append(code(report.branch())).append(".\n\n");
            body.append(scopeNote(scope)).append("\n\n");
        } else {
            body.append("Technolinator could not create the SBOM of this repository, so its dependencies are not tracked in Dependency-Track. ")
                .append("This issue shows the current state of the analysis of ").append(code(report.branch())).append(".\n\n");
        }
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

        var extras = followUp(report, true, false);
        var budget = MAX_BODY_LENGTH - body.length() - footer.length() - extras.length();
        body.append(failureDetails(report, true, "###", budget));
        body.append(extras);
        body.append(footer);
        return body.toString();
    }

    public static String issueCauseChangedComment(FailureReport report) {
        var failures = report.failures();
        var summaries = failures.stream().limit(MAX_SHOWN_FAILURES)
            .map(f -> "- " + code(f.project()) + ": " + f.summary())
            .collect(Collectors.joining("\n"));
        if (failures.size() > MAX_SHOWN_FAILURES) {
            summaries += "\n- and " + (failures.size() - MAX_SHOWN_FAILURES) + " more";
        }
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
        var withMarker = markResolved(body);
        var banner = "> ✅ **Resolved on " + TIME.format(resolvedAt) + "**" + commitSha.map(sha -> " with commit " + code(shortSha(sha))).orElse("")
            + ". The failure below is kept for reference.";
        var markerEnd = withMarker.indexOf("-->");
        if (markerEnd < 0 || withMarker.contains("**Resolved on")) {
            return withMarker;
        }
        var insertAt = markerEnd + "-->".length();
        return withMarker.substring(0, insertAt) + "\n\n" + banner + withMarker.substring(insertAt);
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
        if (report.scope().isPartial()) {
            body.append("Technolinator could not create the SBOM of ").append(report.scope().failed()).append(" of ").append(report.scope().total())
                .append(" projects.\n\n").append(scopeNote(report.scope())).append("\n\n");
        }

        var footer = new StringBuilder();
        footer.append("\n").append(followUp(report, includeLogs, true));
        var other = otherContent != null && !otherContent.isBlank() ? otherContent.strip() : null;
        var separator = "\n---\n\n";
        var closing = "\n" + commentMarker;
        var fixedLength = body.length() + footer.length() + closing.length();

        // The failure details get what the other content, up to its guaranteed share, leaves of the comment,
        // and the other content then everything which the details didn't use.
        var otherReserve = other == null ? 0 : Math.min(other.length() + separator.length() + 1, MAX_OTHER_CONTENT_LENGTH);
        var budget = Math.max(MIN_EXCERPT_LENGTH * 2, MAX_BODY_LENGTH - fixedLength - otherReserve);
        var details = failureDetails(report, includeLogs, "###", budget);

        body.append(details);
        body.append(footer);
        if (other != null) {
            var space = MAX_BODY_LENGTH - body.length() - separator.length() - 1 - closing.length();
            body.append(separator).append(limit(other, Math.max(0, space))).append("\n");
        }
        body.append(closing);
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
            body.append("\n---\n\n").append(limit(otherContent.strip(), MAX_BODY_LENGTH - body.length() - commentMarker.length() - 100)).append("\n");
        }
        body.append("\n").append(commentMarker);
        return body.toString();
    }

    // ---- shared ----

    /**
     * What follows the failure details: where to look up the configuration, and help for AI coding agents.
     * The agents get nothing if logs are hidden, as that is for code of untrusted forks, or if the repository can't fix the cause.
     */
    static String followUp(FailureReport report, boolean includeLogs, boolean pullRequest) {
        var result = new StringBuilder();
        result.append("📖 **Configuration:** the analysis can be tuned per repository, e.g. JDK version, build tool arguments or excluded folders, ")
            .append("see [Repository specific configuration](").append(CONFIG_DOCS_URL).append(").\n\n");
        if (includeLogs && report.failures().stream().anyMatch(f -> f.kind() == FailureKind.REPO_ACTIONABLE)) {
            result.append(aiAgentSection(pullRequest));
        }
        return result.toString();
    }

    private static String aiAgentSection(boolean pullRequest) {
        var subject = pullRequest ? "this pull request" : "the default branch of this repository";
        var location = pullRequest ? "comment on this pull request" : "description of this issue";
        var prompt = (pullRequest ? "@copilot " : "")
            + "Technolinator could not create the SBOM of " + subject + ". "
            + "Read the entire " + location + ", including the collapsed log excerpts, to find the cause. "
            + "Technolinator runs cdxgen, which calls the build tools of this repository on a clean checkout, "
            + "without credentials and with access to public package repositories only. "
            + "Fix the cause in this repository: in the build files and lock files, or in `.github/technolinator.yml`, "
            + "which is documented at " + CONFIG_DOCS_URL + ". "
            + "Keep the change minimal and focused on this failure, and don't remove modules or dependencies just to silence it. "
            + "If the cause can't be fixed in this repository, e.g. because a dependency is private, explain that instead of guessing.";
        var usage = pullRequest
            ? "Post it as a comment on this pull request to ask GitHub Copilot (needs write access and Copilot cloud agent), "
            + "or remove the leading `@copilot` and hand it to any other AI coding agent, together with a link to this pull request."
            : "Assign this issue to Copilot in the sidebar, or hand the prompt to any other AI coding agent, together with a link to this issue.";

        return "<details>\n<summary>🤖 Context and prompt for AI coding agents</summary>\n\n"
            + "Technolinator creates the SBOM of a repository with [cdxgen](https://github.com/CycloneDX/cdxgen), which runs the build tools "
            + "of the repository (Maven, Gradle, npm, ...) on a plain checkout. The log excerpts above show what these tools reported. "
            + "Usually dependencies, plugins or the JDK can't be resolved in such a clean environment without credentials. "
            + "Fixes are typically changes to the build files or to the repository specific configuration "
            + "(`jdk.version`, `maven.args`, `gradle.args`, `analysis.location`, `analysis.excludes`, ...), see "
            + "[Repository specific configuration](" + CONFIG_DOCS_URL + ").\n\n"
            + "**Prompt.** " + usage + "\n\n"
            + fenced(prompt) + "\n\n</details>\n\n";
    }

    /**
     * Tells that the analysis of a multi project repository worked in general, and which part of it did not.
     */
    static String scopeNote(FailureReport.Scope scope) {
        var succeeded = scope.total() - scope.failed();
        var note = new StringBuilder("✅ The other ").append(succeeded).append(succeeded == 1 ? " project was" : " projects were")
            .append(" analysed successfully");
        if (!scope.succeeded().isEmpty()) {
            note.append(" (")
                .append(scope.succeeded().stream().limit(MAX_LISTED_PROJECTS).map(StatusReportRenderer::code).collect(Collectors.joining(", ")))
                .append(scope.succeeded().size() > MAX_LISTED_PROJECTS ? ", ..." : "")
                .append(")");
        }
        return note.append(", only the projects below are affected.").toString();
    }

    static String failureDetails(FailureReport report, boolean includeLogs, String headingLevel, int budget) {
        var failures = report.failures();
        var shown = failures.subList(0, Math.min(MAX_SHOWN_FAILURES, failures.size()));
        var perFailure = Math.max(MIN_EXCERPT_LENGTH, Math.min(MAX_EXCERPT_LENGTH, budget / Math.max(1, shown.size()) - FAILURE_OVERHEAD));

        var result = new StringBuilder();
        for (var failure : shown) {
            result.append(headingLevel).append(' ').append(code(failure.project()));
            if (failure.tool() != null && !failure.tool().isBlank()) {
                result.append(" – probably ").append(code(failure.tool()));
            }
            result.append("\n\n");
            if (failure.location() != null && !failure.location().isBlank()) {
                result.append("**Module:** ").append(code(failure.location())).append("\n\n");
            }
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
                var excerpt = LogExcerpter.condense(failure.excerpt(), perFailure);
                result.append("<details>\n<summary>Log excerpt</summary>\n\n")
                    .append(fenced(excerpt)).append("\n\n</details>\n\n");
            }
        }
        if (failures.size() > shown.size()) {
            var hidden = failures.subList(shown.size(), failures.size());
            result.append("_").append(hidden.size()).append(" more failing projects not shown: ")
                .append(hidden.stream().limit(MAX_LISTED_PROJECTS).map(f -> code(f.project())).collect(Collectors.joining(", ")))
                .append(hidden.size() > MAX_LISTED_PROJECTS ? ", ..." : "")
                .append("_\n\n");
        }
        return result.toString();
    }

    /**
     * Cuts the content to the given length, if it is longer, as GitHub rejects texts which are too long.
     */
    static String limit(String content, int maxLength) {
        return Commons.truncate(content, maxLength, "\n\n_The report is truncated, as it exceeds the size limit of GitHub comments._");
    }

    /**
     * A code block which can't be broken out of by the content.
     */
    static String fenced(String content) {
        var fence = "`".repeat(Math.max(3, longestBacktickRun(content) + 1));
        return fence + "\n" + content + "\n" + fence;
    }

    /**
     * Inline code which can't be broken out of by the content, and is cut if it is unreasonably long.
     */
    static String code(String content) {
        var single = LINE_BREAKS.matcher(content).replaceAll(" ").strip();
        if (single.length() > MAX_INLINE_LENGTH) {
            single = single.substring(0, MAX_INLINE_LENGTH) + " [...]";
        }
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
