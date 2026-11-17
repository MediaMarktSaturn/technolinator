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
    List<ToolFailure> failures
) {

    /**
     * A single failed tool run, e.g. the cdxgen run for one project of the repository.
     *
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
        FailureKind kind
    ) {
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
