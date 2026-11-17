package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.sbom.VulnerabilityReporting;
import io.quarkus.logging.Log;

import java.util.Locale;

/**
 * The one log line for everything Technolinator writes to GitHub to report about an analysis:
 * status issues and pull-request comments. All of them are found by searching for {@link #TAG}.
 * <pre>
 * GITHUB_REPORT type=pull_request kind=vuln action=created url=https://github.com/org/repo/pull/1#issuecomment-2
 * </pre>
 * Values are joined by underscores, so that log search splitting words at hyphens can't confuse e.g. {@code no_vuln} with {@code vuln}.
 */
public final class GitHubReportLog {

    public static final String TAG = "GITHUB_REPORT";

    public enum Type {
        ISSUE,
        PULL_REQUEST
    }

    public enum Kind {
        /**
         * Vulnerability report without findings.
         */
        NO_VULN,
        /**
         * Vulnerability report with findings.
         */
        VULN,
        /**
         * The SBOM could not be created. Status issues are always about that.
         */
        ERROR
    }

    private GitHubReportLog() {
    }

    /**
     * @param action what was done, like {@code created}
     * @param url    link to the issue or comment written
     */
    static void written(Type type, Kind kind, String action, String url) {
        Log.infof("%s type=%s kind=%s action=%s url=%s",
            TAG, lower(type), lower(kind), action, url == null || url.isBlank() ? "-" : url);
    }

    /**
     * @param reportText the vulnerability report, null if there is none
     */
    static Kind kindOfReport(String reportText) {
        return VulnerabilityReporting.hasFindings(reportText) ? Kind.VULN : Kind.NO_VULN;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
