package com.mediamarktsaturn.technolinator.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import org.cyclonedx.exception.ParseException;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static com.mediamarktsaturn.technolinator.report.FailureKind.INFRASTRUCTURE;
import static com.mediamarktsaturn.technolinator.report.FailureKind.REPO_ACTIONABLE;
import static com.mediamarktsaturn.technolinator.report.FailureKind.TIMEOUT;

/**
 * Decides who is responsible for a failure, which tool probably failed, and what could help.
 * <p>
 * cdxgen delegates to the build tools of the analysed project, and only relays their output.
 * So which tool failed is a heuristic on that output, and always presented as "probably".
 */
public final class FailureClassifier {

    private static final String JDK_HINT = "The JDK used by cdxgen doesn't fit the project. Select a matching one via `jdk.version` in `.github/technolinator.yml`.";
    private static final String CREDENTIALS_HINT = "A repository or registry requires credentials, which Technolinator doesn't have. "
        + "Make the dependencies available from a public source, or exclude the affected module via `analysis.excludes`.";
    private static final String SCOPE_HINT = "Reduce the analysed scope via `analysis.location` or `analysis.excludes`, or analyse modules separately via `projects`.";

    /**
     * @param errorLine the line of the output the classification is based on, may be empty
     * @param hint      advice how to fix the failure, may be empty
     */
    public record Classification(
        FailureKind kind,
        String tool,
        String summary,
        String errorLine,
        String hint,
        String errorHash
    ) {
    }

    private record Signature(Pattern pattern, String tool, FailureKind kind, String summary, String hint) {
        static Signature of(String regex, String tool, String summary, String hint) {
            return new Signature(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), tool, REPO_ACTIONABLE, summary, hint);
        }

        static Signature timeout(String regex, String summary, String hint) {
            return new Signature(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), null, TIMEOUT, summary, hint);
        }
    }

    /**
     * Known failure causes, the more specific ones first. Of all matches in an output the most specific wins,
     * the first line wins on ties. A tool of {@code null} means it is guessed from the surrounding output.
     */
    private static final List<Signature> SIGNATURES = List.of(
        Signature.of("OutOfMemoryError|JavaScript heap out of memory|Cannot allocate memory", null,
            "The process ran out of memory",
            SCOPE_HINT + " Parallel builds need more memory, e.g. `GRADLE_MULTI_THREADED: \"false\"` can be set in `env`."),
        Signature.of("Unsupported class file major version|UnsupportedClassVersionError|invalid (target|source) release|release version \\d+ not supported"
                + "|compiled by a more recent version of the Java Runtime|requires Java \\d+|Unsupported Java|Dependency requires at least JVM runtime version"
                + "|Could not target platform", null,
            "The JDK doesn't match the project", JDK_HINT),
        Signature.timeout("timed out|timeout exceeded|ETIMEDOUT|ESOCKETTIMEDOUT", "A step of the analysis ran into a timeout",
            "Large repositories can be narrowed via `analysis.location` or `analysis.excludes`."),
        Signature.of("401 Unauthorized|403 Forbidden|Authentication failed|Access denied|status code:? 40[13]|\\bE40[13]\\b", null,
            "A repository or registry denied access", CREDENTIALS_HINT),
        Signature.of("Could not resolve dependencies|Could not find artifact|Failed to collect dependencies|Non-resolvable (import|parent) POM"
                + "|Could not transfer artifact|Failed to read artifact descriptor|The following artifacts could not be resolved", "maven",
            "Maven could not resolve dependencies",
            "A dependency or parent POM can't be resolved. Check that it exists and that its repository is reachable by Technolinator, "
                + "which only has access to public repositories. Maven options can be set via `maven.args`."),
        Signature.of("Could not resolve all (files|dependencies|artifacts)|Could not resolve [\\w.-]+:[\\w.-]+|Could not GET|Could not HEAD"
                + "|Plugin \\[id: .*was not found|Could not find [\\w.-]+:[\\w.-]+", "gradle",
            "Gradle could not resolve dependencies",
            "A dependency or plugin can't be resolved. Check that it exists and that its repository is reachable by Technolinator, "
                + "which only has access to public repositories. Gradle options can be set via `gradle.args`."),
        Signature.of("npm (ERR!|error) (code )?(E404|ENOTFOUND|ERESOLVE|ETARGET|EINTEGRITY)|Could not resolve dependency|ERESOLVE", "npm",
            "npm could not install the dependencies",
            "Check that `package.json` and the lock file are consistent (`npm ci` works on a clean checkout), "
                + "that peer dependency conflicts are resolved and that all packages come from public registries."),
        Signature.of("ERR_PNPM_[A-Z_]+|YN\\d{4}|error An unexpected error occurred", null,
            "The package manager could not install the dependencies",
            "Check that the lock file is consistent with the manifest (a frozen install works on a clean checkout) and that all packages are publicly available."),
        Signature.of("go: .*(cannot find module|missing go\\.sum entry|no required module|invalid version|unknown revision|updates to go\\.(mod|sum) needed)"
                + "|cannot find package|go\\.mod file not found|malformed module path", "go",
            "Go could not resolve modules",
            "Run `go mod tidy` locally and commit `go.mod` and `go.sum`. Private modules can't be fetched by Technolinator."),
        Signature.of("ERROR: (Could not find a version that satisfies|No matching distribution found|Failed building wheel|Failed to build)"
                + "|subprocess-exited-with-error|SolverProblemError", "pip",
            "Python dependencies could not be resolved",
            "Check that all pinned versions exist and can be installed on Linux without system libraries missing in the Technolinator image."),
        Signature.of("error: failed to (select a version|load manifest|get|parse manifest)|no matching package named|failed to resolve patches", "cargo",
            "Cargo could not resolve dependencies",
            "Check that `Cargo.lock` is up to date and that all crates and git dependencies are publicly available."),
        Signature.of("error (NU\\d{4}|NETSDK\\d{4}|MSB\\d{4})", "dotnet",
            "dotnet could not restore the project",
            "Check package sources, target frameworks and the required SDK version."),
        Signature.of("What went wrong|FAILURE: Build failed with an exception|Could not compile (build|settings) file", "gradle",
            "The Gradle build failed",
            "Check that `./gradlew dependencies` works on a clean checkout. Gradle options can be set via `gradle.args`."),
        Signature.of("Failed to execute goal|BUILD FAILURE|Malformed POM|Child module .* does not exist", "maven",
            "The Maven build failed",
            "Check that `mvn validate` works on a clean checkout. Maven options can be set via `maven.args`."),
        Signature.of("npm (ERR!|error)", "npm",
            "npm failed", "Check that `npm ci` works on a clean checkout.")
    );

    private record ToolMarker(Pattern pattern, String tool) {
        static ToolMarker of(String regex, String tool) {
            return new ToolMarker(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), tool);
        }
    }

    private static final List<ToolMarker> TOOL_MARKERS = List.of(
        ToolMarker.of("gradle", "gradle"),
        ToolMarker.of("\\bmvn\\b|\\bmaven\\b|\\[(INFO|ERROR|WARNING)]", "maven"),
        ToolMarker.of("\\bpnpm\\b|ERR_PNPM", "pnpm"),
        ToolMarker.of("\\byarn\\b|\\bYN\\d{4}\\b", "yarn"),
        ToolMarker.of("\\bnpm\\b", "npm"),
        ToolMarker.of("^go: |\\bgo (list|mod|build)\\b", "go"),
        ToolMarker.of("\\bpip3?\\b|\\bpoetry\\b", "pip"),
        ToolMarker.of("\\bcargo\\b", "cargo"),
        ToolMarker.of("\\bdotnet\\b|\\bnuget\\b", "dotnet")
    );

    static final String CDXGEN = "cdxgen";

    private FailureClassifier() {
        // utility
    }

    public static Classification classify(FailureStage stage, Throwable cause) {
        return switch (stage) {
            case CHECKOUT -> infrastructure("git", "The repository could not be checked out", cause);
            case UPLOAD -> infrastructure("dependency-track", "The SBOM could not be uploaded to Dependency-Track", cause);
            case SBOM_GENERATION -> classifySbomGeneration(cause);
        };
    }

    private static Classification classifySbomGeneration(Throwable cause) {
        if (cause instanceof ProcessFailedException process) {
            return classifyProcess(process);
        }
        if (cause instanceof TimeoutException || cause instanceof io.smallrye.mutiny.TimeoutException) {
            return new Classification(TIMEOUT, "technolinator", "The analysis did not finish in time", "",
                "Large repositories can be narrowed via `analysis.location` or `analysis.excludes`.",
                hash("technolinator|timeout"));
        }
        if (cause instanceof ParseException || cause instanceof JsonProcessingException) {
            var message = firstLine(cause.getMessage());
            return new Classification(REPO_ACTIONABLE, CDXGEN, "cdxgen created an SBOM which couldn't be read", message, "",
                hash(CDXGEN + "|unreadable sbom"));
        }
        return infrastructure("technolinator", "The analysis failed unexpectedly", cause);
    }

    private static Classification infrastructure(String tool, String summary, Throwable cause) {
        var message = cause == null ? "" : cause.getClass().getSimpleName() + ": " + firstLine(cause.getMessage());
        return new Classification(INFRASTRUCTURE, tool, summary, message, "", hash(tool + "|" + normalize(message)));
    }

    static Classification classifyProcess(ProcessFailedException failure) {
        var lines = LogExcerpter.cleanLines(failure.outputLines());

        var bestSignature = -1;
        var bestLine = -1;
        for (var lineIdx = 0; lineIdx < lines.size() && bestSignature != 0; lineIdx++) {
            var line = lines.get(lineIdx);
            var limit = bestSignature < 0 ? SIGNATURES.size() : bestSignature;
            for (var sigIdx = 0; sigIdx < limit; sigIdx++) {
                if (SIGNATURES.get(sigIdx).pattern().matcher(line).find()) {
                    bestSignature = sigIdx;
                    bestLine = lineIdx;
                    break;
                }
            }
        }

        if (bestSignature >= 0) {
            var signature = SIGNATURES.get(bestSignature);
            var errorLine = lines.get(bestLine).strip();
            var tool = signature.tool() != null ? signature.tool() : guessTool(lines, bestLine);
            return new Classification(signature.kind(), tool, signature.summary(), errorLine, signature.hint(),
                hash(tool + "|" + normalize(errorLine)));
        }

        var errorLine = LogExcerpter.firstErrorLine(lines);
        var exitCode = failure.exitCode();
        if (exitCode != null && exitCode == 137) {
            return new Classification(REPO_ACTIONABLE, CDXGEN, "The analysis was killed (exit code 137), most probably it ran out of memory",
                errorLine, SCOPE_HINT, hash(CDXGEN + "|137"));
        }
        var tool = errorLine.isEmpty() ? CDXGEN : guessTool(lines, Math.max(0, lines.indexOf(errorLine)));
        var summary = "%s failed with exit code %s".formatted(tool, exitCode);
        var hashSource = errorLine.isEmpty() ? summary : errorLine;
        return new Classification(REPO_ACTIONABLE, tool, summary, errorLine, "", hash(tool + "|" + normalize(hashSource)));
    }

    /**
     * The most recently invoked tool before the failing line is the best guess, as cdxgen runs one tool after another.
     */
    static String guessTool(List<String> lines, int errorLineIdx) {
        for (var i = Math.min(errorLineIdx, lines.size() - 1); i >= 0; i--) {
            var tool = toolOf(lines.get(i));
            if (tool != null) {
                return tool;
            }
        }
        for (var i = errorLineIdx + 1; i < lines.size(); i++) {
            var tool = toolOf(lines.get(i));
            if (tool != null) {
                return tool;
            }
        }
        return CDXGEN;
    }

    private static String toolOf(String line) {
        for (var marker : TOOL_MARKERS) {
            if (marker.pattern().matcher(line).find()) {
                return marker.tool();
            }
        }
        return null;
    }

    /**
     * Makes error lines comparable across runs: no numbers, hashes, or varying whitespace.
     */
    static String normalize(String line) {
        return line.toLowerCase(Locale.ROOT)
            .replaceAll("[0-9a-f]{7,}", "#")
            .replaceAll("\\d+", "#")
            .replaceAll("\\s+", " ")
            .strip();
    }

    static String hash(String value) {
        return FailureReport.sha256Hex(value).substring(0, 8);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        var line = message.lines().findFirst().orElse("").strip();
        return line.length() > 200 ? line.substring(0, 200) + " [...]" : line;
    }
}
