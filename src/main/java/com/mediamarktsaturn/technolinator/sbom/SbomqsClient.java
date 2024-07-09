package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;

@ApplicationScoped
public class SbomqsClient {

    /**
     * 'sbomqs' command with the following options:
     * * score # calculate score for sbom file
     * * -b # short output: no detailed scoring, just overall score in form '8.5     path/to/the/sbom.json'
     * * %s # sbom file path
     */
    private static final String SBOMQS_COMMAND = "sbomqs score -b %s";

    /**
     * Pattern of a valid score output. Examples:
     * * 8.2     cdx     1.4     json    /var/home/heubeck/w/technolinator/src/test/resources/sbom/vulnerable.json
     * * 6.1     cdx     1.4     json    /var/home/heubeck/w/technolinator/src/test/resources/sbom/not-vulnerable.json
     * * failed to parse /var/home/heubeck/w/technolinator/src/test/resources/sbom/unkown.json : unsupported sbom format
     */
    private static final String SCORE_RESULT_SUFFIX_PATTERN = "\\s+cdx\\s+\\d+(\\.?\\d+){0,1}\\s+json\\s+%s";
    private static final String SCORE_RESULT_PATTERN = "^\\d+(\\.?\\d+){0,1}" + SCORE_RESULT_SUFFIX_PATTERN + "$";

    /**
     * Calculates a quality score for the given sbomFile using the 'sbomqs' tool.
     */
    public Uni<Result<QualityScore>> calculateQualityScore(Path sbomFile) {
        var filename = sbomFile.toAbsolutePath().toString();
        var command = SBOMQS_COMMAND.formatted(filename);

        return ProcessHandler.run(command)
            .map(r -> mapResult(r, filename))
            .onFailure().recoverWithItem(failure -> Result.failure(failure.getCause()));
    }

    Result<QualityScore> mapResult(ProcessHandler.ProcessResult result, String filename) {
        return switch (result) {
            case ProcessHandler.ProcessResult.Success s -> parseScore(s, filename);
            case ProcessHandler.ProcessResult.Failure f -> Result.failure(f.cause());
        };
    }

    Result<QualityScore> parseScore(ProcessHandler.ProcessResult.Success result, String filename) {
        var regexEscapedFilename = escapeFilenameAsRegexPattern(filename);
        return result.outputLines().stream()
            .filter(l -> l.matches(SCORE_RESULT_PATTERN.formatted(regexEscapedFilename)))
            .findFirst()
            .map(scoreLine -> {
                var score = scoreLine.replaceFirst(SCORE_RESULT_SUFFIX_PATTERN.formatted(regexEscapedFilename), "").trim();
                Log.infof("Quality score for %s: %s", filename, score);
                return Result.success(new QualityScore(score));
            }).orElseGet(() -> Result.failure(
                new IllegalStateException("'sbomqs' did not output a score for " + filename)
            ));
    }

    private static String escapeFilenameAsRegexPattern(String filename) {
        return filename
            .replace("\\", "\\\\")
            .replace("/", "\\/")
            .replace(".", "\\.");
    }

    public record QualityScore(String score) {
    }

}
