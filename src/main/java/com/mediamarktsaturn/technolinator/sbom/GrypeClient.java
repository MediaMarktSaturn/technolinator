package com.mediamarktsaturn.technolinator.sbom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Wraps around grype for creating vulnerability reports
 */
@ApplicationScoped
public class GrypeClient {

    private static final String OUTPUT_FILE = "report.txt";

    private final Path templateFile;

    public GrypeClient(
        @ConfigProperty(name = "grype.template")
        String templateFile
    ) {
        this.templateFile = Paths.get(templateFile);
        if (!Files.isReadable(this.templateFile)) {
            throw new IllegalStateException("Template file not readable: " + templateFile);
        }
    }

    /**
     * 'grype' command with the following options:
     * * -q # silent
     * * --by-cve # output cve of vulnerability instead of original identifier, if available
     * * -o template # output should be templated
     * * -t %s # template file to use
     * * --file %s # output file to use
     * * sbom:%s # sbom file location as input
     */
    private static final String GRYPE_COMMAND = "grype -q --by-cve -o template -t %s --file %s sbom:%s";

    /**
     * Creates a vulnerability report using grype for the given [sbomFile]
     */
    public Uni<Result<VulnerabilityReport>> createVulnerabilityReport(Path sbomFile) {
        var command = GRYPE_COMMAND.formatted(
            templateFile.toAbsolutePath().toString(),
            OUTPUT_FILE,
            sbomFile.toAbsolutePath().toString()
        );

        var reportDir = sbomFile.getParent();
        return ProcessHandler.run(command, reportDir, Map.of())
            .map(result -> createReport(result, reportDir))
            .onFailure().recoverWithItem(failure -> Result.failure(failure.getCause()));
    }

    Result<VulnerabilityReport> createReport(ProcessHandler.ProcessResult result, Path reportDir) {
        return switch (result) {
            case ProcessHandler.ProcessResult.Success s -> parseReport(reportDir);
            case ProcessHandler.ProcessResult.Failure f -> Result.failure(f.cause());
        };
    }

    Result<VulnerabilityReport> parseReport(Path reportDir) {
        var reportFile = reportDir.resolve(OUTPUT_FILE);
        try {
            if (Files.isReadable(reportFile)) {
                String report = Files.readString(reportFile);
                return Result.success(new VulnerabilityReport.Report(report));
            } else {
                return Result.success(new VulnerabilityReport.None());
            }
        } catch (IOException e) {
            Log.errorf(e, "Failed to read vulnerability report from %s", reportFile);
            return Result.failure(e);
        }
    }


    sealed interface VulnerabilityReport {
        record Report(String text) implements VulnerabilityReport {
        }

        record None() implements VulnerabilityReport {
        }
    }

}
