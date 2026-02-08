package com.mediamarktsaturn.technolinator.sbom;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class SbomqsClient {

    /**
     * 'sbomqs' command with the following options:
     * * score # calculate score for sbom file
     * * -b # short output: no detailed scoring, just overall score in form '8.5     path/to/the/sbom.json'
     * * %s # sbom file path
     */
    private static final String SBOMQS_COMMAND = "sbomqs score --profile bsi --json %s";

    private final ObjectMapper objectMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

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
        var sbomqsOutput = String.join("", result.outputLines());

        try {
            return objectMapper.readValue(sbomqsOutput, SbomqsJsonResult.class).files().stream().findFirst().map(sbomqsFileResult -> {
                    var score = sbomqsFileResult.score().setScale(1, RoundingMode.HALF_UP).toString();
                    Log.infof("Quality score for %s: %s", filename, score);
                    return Result.success(new QualityScore(score));
                }
            ).orElseGet(() -> Result.failure(
                new IllegalStateException("'sbomqs' did not output a score for " + filename)
            ));
        } catch (Exception e) {
            Log.errorv(e, "Failed to calculate score for %s", filename);
            return Result.failure(
                new IllegalStateException("'sbomqs' did not output a score for " + filename, e)
            );
        }
    }

    public record QualityScore(String score) {
    }

}

record SbomqsJsonResult(
    List<SbomqsJsonResultFile> files
) {
}

record SbomqsJsonResultFile(
    @JsonProperty("sbom_quality_score")
    BigDecimal score,
    String grade
) {
}
