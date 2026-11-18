package com.mediamarktsaturn.technolinator.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Everything worth knowing about a failed analysis which is going to be shown to the repository owners.
 * All texts are already cleaned from secrets.
 *
 * @param started when the analysis was started, used to ignore results which were overtaken by a newer analysis
 */
public record FailureReport(
    String repoFullName,
    String repoHtmlUrl,
    String branch,
    Optional<String> commitSha,
    Instant started,
    String traceId,
    List<ToolFailure> failures,
    Scope scope
) {

    public FailureReport(
        String repoFullName,
        String repoHtmlUrl,
        String branch,
        Optional<String> commitSha,
        Instant started,
        String traceId,
        List<ToolFailure> failures
    ) {
        this(repoFullName, repoHtmlUrl, branch, commitSha, started, traceId, failures, Scope.UNKNOWN);
    }

    /**
     * How much of a repository, which is analysed as several projects (see `projects` in the repository configuration),
     * could be analysed. Not part of the {@link #errorHash()}, as it doesn't tell why something failed.
     *
     * @param total     number of projects analysed, 0 if unknown
     * @param failed    number of projects whose analysis failed
     * @param succeeded names of the projects whose analysis succeeded and yielded an SBOM
     */
    public record Scope(int total, int failed, List<String> succeeded) {

        public static final Scope UNKNOWN = new Scope(0, 0, List.of());

        public Scope {
            succeeded = List.copyOf(succeeded);
        }

        /**
         * @return whether the repository has several projects, of which only some failed
         */
        public boolean isPartial() {
            return total > 1 && failed > 0 && failed < total;
        }

        /**
         * @return whether the repository has several projects
         */
        public boolean isMultiProject() {
            return total > 1;
        }
    }

    /**
     * A single failed tool run, e.g. the cdxgen run for one project of the repository.
     *
     * @param location  the analysed folder relative to the repository root, empty for the root itself or if unknown
     * @param tool      the tool which most probably failed, determined heuristically from the output
     * @param errorLine the line of the output which caused the classification, may be empty
     * @param excerpt   the most relevant parts of the output, may be empty
     * @param kind      whom the failure is attributable to, only repository fixable failures and repeated timeouts get reported at all
     */
    public record ToolFailure(
        String project,
        String tool,
        String command,
        Integer exitCode,
        String summary,
        String errorLine,
        String hint,
        String excerpt,
        String errorHash,
        FailureKind kind,
        String location
    ) {

        public ToolFailure(
            String project,
            String tool,
            String command,
            Integer exitCode,
            String summary,
            String errorLine,
            String hint,
            String excerpt,
            String errorHash,
            FailureKind kind
        ) {
            this(project, tool, command, exitCode, summary, errorLine, hint, excerpt, errorHash, kind, "");
        }
    }

    /**
     * Identifies the cause of the failure(s), independent of paths, numbers and timestamps.
     * A changed hash means the repository fails for another reason than before.
     */
    public String errorHash() {
        var joined = failures.stream().map(ToolFailure::errorHash).sorted().reduce("", (a, b) -> a + "," + b);
        return FailureClassifier.hash(joined);
    }

    static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
