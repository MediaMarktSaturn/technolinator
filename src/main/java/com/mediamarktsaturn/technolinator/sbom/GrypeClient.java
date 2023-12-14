package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import io.smallrye.mutiny.Uni;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps around grype for creating vulnerability reports
 */
public class GrypeClient extends VulnerabilityReporting {

    private static final Map<String, String> DEFAULT_ENV = Map.of(
        "GRYPE_DB_AUTO_UPDATE", "true"
    );

    private final Optional<Path> templateFile;
    private final Optional<String> configFile;

    public GrypeClient(
        Optional<String> templateFile,
        Optional<String> configFile
    ) {
        this.templateFile = templateFile.map(Paths::get);
        this.templateFile.ifPresent(template -> {
            if (!Files.isReadable(template)) {
                throw new IllegalStateException("Template file not readable: " + template);
            }
        });
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
    @Override
    public Uni<Result<VulnerabilityReport>> createVulnerabilityReport(Path sbomFile, String projectName) {
        if (templateFile.isEmpty()) {
            return noReport;
        }

        var command = GRYPE_COMMAND.formatted(
            templateFile.get().toAbsolutePath().toString(),
            OUTPUT_FILE,
            sbomFile.toAbsolutePath().toString(),
            configFile.map("-c "::concat).orElseGet(String::new)
        );

        var reportDir = sbomFile.getParent();
        return ProcessHandler.run(command, reportDir, DEFAULT_ENV)
            .map(result -> createReport(result, reportDir, projectName))
            .onFailure().recoverWithItem(failure -> Result.failure(failure.getCause()));
    }

}
