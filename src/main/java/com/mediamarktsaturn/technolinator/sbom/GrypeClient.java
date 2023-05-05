package com.mediamarktsaturn.technolinator.sbom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

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

    private static final Map<String, String> DEFAULT_ENV = Map.of(
        "GRYPE_CHECK_FOR_APP_UPDATE", "false",
        "GRYPE_DB_AUTO_UPDATE", "true"
    );

    private final Path templateFile;
    private final Optional<String> configFile;

    public GrypeClient(
        @ConfigProperty(name = "grype.template")
        String templateFile,
        @ConfigProperty(name = "grype.config")
        Optional<String> configFile
    ) {
        this.templateFile = Paths.get(templateFile);
        if (!Files.isReadable(this.templateFile)) {
            throw new IllegalStateException("Template file not readable: " + templateFile);
        }
        this.configFile = configFile;
        this.configFile.map(Paths::get).ifPresent(config -> {
            if (!Files.isReadable(config)) {
                throw new IllegalStateException("Config file not readable: " + config);
            }
        });
    }

    /**
     * 'grype' command with the following options:
     * * -q # silent
     * * --by-cve # output cve of vulnerability instead of original identifier, if available
     * * -o template # output should be templated
     * * -t %s # template file to use
     * * --file %s # output file to use
     * * sbom:%s # sbom file location as input
     * * %s: # '-c config.file' added at the end
     */
    private static final String GRYPE_COMMAND = "grype -q --by-cve -o template -t %s --file %s sbom:%s %s";

    /**
     * Creates a vulnerability report using grype for the given [sbomFile]
     */
    public Uni<Result<VulnerabilityReport>> createVulnerabilityReport(Path sbomFile) {
        var command = GRYPE_COMMAND.formatted(
            templateFile.toAbsolutePath().toString(),
            OUTPUT_FILE,
            sbomFile.toAbsolutePath().toString(),
            configFile.map("-c "::concat).orElseGet(String::new)
        );

        var reportDir = sbomFile.getParent();
        return ProcessHandler.run(command, reportDir, DEFAULT_ENV)
            .map(result -> createReport(result, reportDir))
            .onFailure().recoverWithItem(failure -> Result.failure(failure.getCause()));
    }

    Result<VulnerabilityReport> createReport(ProcessHandler.ProcessResult result, Path reportDir) {
        return switch (result) {
            case ProcessHandler.ProcessResult.Success ignored -> parseReport(reportDir);
            case ProcessHandler.ProcessResult.Failure f -> Result.failure(f.cause());
        };
    }

    Result<VulnerabilityReport> parseReport(Path reportDir) {
        var reportFile = reportDir.resolve(OUTPUT_FILE);
        try {
            if (Files.isReadable(reportFile)) {
                String report = Files.readString(reportFile);
                return Result.success(VulnerabilityReport.report(report));
            } else {
                return Result.success(VulnerabilityReport.none());
            }
        } catch (IOException e) {
            Log.errorf(e, "Failed to read vulnerability report from %s", reportFile);
            return Result.failure(e);
        }
    }

    public sealed interface VulnerabilityReport {
        record Report(String text) implements VulnerabilityReport {
        }

        record None() implements VulnerabilityReport {
        }

        static VulnerabilityReport report(String text) {
            return new Report(text);
        }

        static VulnerabilityReport none() {
            return new None();
        }
    }

}
