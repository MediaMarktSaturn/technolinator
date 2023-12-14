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
 * Wraps around depscan for creating vulnerability reports
 */
public class DepscanClient extends VulnerabilityReporting {

    private static final String OUTPUT_DIR = "depscan-reports";
    private static final String OUTPUT_FILE = "report.txt";

    private final Optional<Path> templateFile;

    public DepscanClient(Optional<String> templateFile) {
        this.templateFile = templateFile.map(Paths::get);
        this.templateFile.ifPresent(template -> {
            if (!Files.isReadable(template)) {
                throw new IllegalStateException("Template file not readable: " + template);
            }
        });
    }

    /**
     * 'depscan' command with the following options:
     * * --no-vuln-table # no console report
     * * --no-banner # no logo printed to console
     * * --reports-dir %s # location of reports
     * * --report-template %s # Jinja2 template used for report generation
     * * --report-name %s # filename of the custom report
     * * --bom %s # sbom to analyse
     */
    private static final String DEPSCAN_COMMAND = "depscan --no-vuln-table --no-banner --reports-dir %s --report-template %s --report-name %s --bom %s";

    /**
     * Creates a vulnerability report using grype for the given [sbomFile]
     */
    @Override
    public Uni<Result<VulnerabilityReport>> createVulnerabilityReport(Path sbomFile, String projectName) {
        if (templateFile.isEmpty()) {
            return noReport;
        }

        var command = DEPSCAN_COMMAND.formatted(
            OUTPUT_DIR,
            templateFile.get().toAbsolutePath().toString(),
            OUTPUT_FILE,
            sbomFile.toAbsolutePath().toString()
        );

        var reportDir = sbomFile.getParent();
        return ProcessHandler.run(command, reportDir, Map.of())
            .map(result -> createReport(result, reportDir.resolve(OUTPUT_DIR), projectName))
            .onFailure().recoverWithItem(failure -> Result.failure(failure.getCause()));
    }

}
