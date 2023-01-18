package com.mediamarktsaturn.ghbot.sbom;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.enterprise.context.ApplicationScoped;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.os.ProcessHandler;

@ApplicationScoped
public class CdxgenClient {

    private static final String SBOM_JSON = "sbom.json";

    /**
     * cdxgen option -r: Recurse mode suitable for mono-repos
     * Used for projects containing multiple dependency files like pom.xml & yarn.lock
     */
    private static final String RECURSIVE_FLAG = " -r";

    private static final List<String> WRAPPER_SCRIPT_NAMES = List.of("mvnw", "mvnw.bat", "mvnw.cmd", "gradlew", "gradlew.bat", "gradlew.cmd");

    private final Map<String, String> cdxgenEnv;
    private final boolean cleanWrapperScripts;

    public CdxgenClient(
        @ConfigProperty(name = "github.token")
        String githubToken,
        @ConfigProperty(name = "cdxgen.fetch_license")
        boolean fetchLicense,
        @ConfigProperty(name = "cdxgen.use_gosum")
        boolean useGosum,
        @ConfigProperty(name = "app.clean_wrapper_scripts")
        boolean cleanWrapperScripts
    ) {
        this.cleanWrapperScripts = cleanWrapperScripts;

        // https://github.com/AppThreat/cdxgen#environment-variables
        this.cdxgenEnv = Map.of(
            "GITHUB_TOKEN", githubToken.trim(),
            "FETCH_LICENSE", Boolean.toString(fetchLicense),
            "USE_GOSUM", Boolean.toString(useGosum),
            "MVN_ARGS", "-B -ntp",
            "CDXGEN_TIMEOUT_MS", Integer.toString(10 * 60 * 1000)
        );
    }

    private static final String CDXGEN_CMD_FMT = "cdxgen --fail-on-error -o %s%s";

    public CompletableFuture<SBOMGenerationResult> generateSBOM(File repoDir, Optional<TechnolinatorConfig> config) {
        String cdxgenCmd = CDXGEN_CMD_FMT.formatted(
            SBOM_JSON,
            config.map(TechnolinatorConfig::analysis).map(TechnolinatorConfig.AnalysisConfig::recursive).orElse(false) ? RECURSIVE_FLAG : ""
        );
        Function<ProcessHandler.ProcessResult, SBOMGenerationResult> mapResult = (ProcessHandler.ProcessResult processResult) -> {
            if (processResult instanceof ProcessHandler.ProcessResult.Success) {
                return readAndParseSBOM(new File(repoDir, SBOM_JSON));
            } else {
                var failure = (ProcessHandler.ProcessResult.Failure) processResult;
                return new SBOMGenerationResult.Failure("Command failed: " + cdxgenCmd, failure.cause());
            }
        };

        var future = ProcessHandler.run(cdxgenCmd, prepareForAnalysis(repoDir.getAbsoluteFile()), cdxgenEnv);
        return future.thenApply(mapResult);
    }

    static SBOMGenerationResult readAndParseSBOM(File sbomFile) {
        if (!sbomFile.exists()) {
            return new SBOMGenerationResult.None();
        } else if (sbomFile.canRead()) {
            try {
                return parseSBOM(Files.readAllBytes(sbomFile.toPath()));
            } catch (Exception e) {
                return new SBOMGenerationResult.Failure("Failed to parse " + sbomFile.getAbsolutePath(), e);
            }
        } else {
            return new SBOMGenerationResult.Failure("Cannot read file " + sbomFile.getAbsolutePath(), null);
        }
    }

    private static SBOMGenerationResult parseSBOM(byte[] sbomContent) {
        try {
            final var jsonSBOMParser = new JsonParser();
            final var validationResult = jsonSBOMParser.validate(sbomContent);
            if (!validationResult.isEmpty()) {
                return new SBOMGenerationResult.Invalid(validationResult);
            }

            final var sbom = jsonSBOMParser.parse(sbomContent);

            String group = null;
            String name = null;
            String version = null;
            if (sbom.getMetadata() != null && sbom.getMetadata().getComponent() != null) {
                group = sbom.getMetadata().getComponent().getGroup();
                name = sbom.getMetadata().getComponent().getName();
                version = sbom.getMetadata().getComponent().getVersion();
            } else if (sbom.getMetadata().getComponent() == null && sbom.getComponents().isEmpty() && sbom.getDependencies().isEmpty() && sbom.getServices().isEmpty()) {
                return new SBOMGenerationResult.None();
            }

            if (group != null && name != null && version != null) {
                return new SBOMGenerationResult.Proper(sbom, group, name, version);
            } else {
                return new SBOMGenerationResult.Fallback(sbom);
            }
        } catch (ParseException parseException) {
            return new SBOMGenerationResult.Failure("SBOM file is invalid", parseException);
        } catch (IOException ioException) {
            return new SBOMGenerationResult.Failure("SBOM file could not be read", ioException);
        }
    }

    File prepareForAnalysis(File dir) {
        if (cleanWrapperScripts) {
            WRAPPER_SCRIPT_NAMES.forEach(name -> {
                var file = new File(dir, name);
                if (file.exists()) {
                    file.delete();
                }
            });
        }
        return dir;
    }

    public sealed interface SBOMGenerationResult {
        record Proper(
            Bom sbom,
            String group,
            String name,
            String version
        ) implements SBOMGenerationResult {
        }

        record Fallback(
            Bom sbom
        ) implements SBOMGenerationResult {
        }

        record Invalid(
            List<ParseException> validationIssues
        ) implements SBOMGenerationResult {
        }

        record None() implements SBOMGenerationResult {
        }

        record Failure(
            String message,
            Throwable cause
        ) implements SBOMGenerationResult {
        }
    }

}
