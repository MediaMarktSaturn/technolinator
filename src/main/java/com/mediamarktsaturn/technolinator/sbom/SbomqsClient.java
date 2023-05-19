package com.mediamarktsaturn.technolinator.sbom;

import java.nio.file.Path;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

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
     * * 8.6     src/test/resources/sbom/vulnerable.json
     * * 6.4     src/test/resources/sbom/not-vulnerable.json
     * * 4       awesome-sbom.json
     */
    private static final String SCORE_RESULT_PATTERN = "^\\d+(\\.?\\d+){0,1}\\s+%s$";

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
        return result.outputLines().stream()
            .filter(l -> l.matches(SCORE_RESULT_PATTERN.formatted(escapeFilenameAsRegexPattern(filename))))
            .findFirst()
            .map(scoreLine -> {
                var score = scoreLine.replace(filename, "").trim();
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
