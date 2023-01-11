package com.mediamarktsaturn.ghbot.sbom;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.enterprise.context.ApplicationScoped;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.ghbot.os.ProcessHandler;

@ApplicationScoped
public class CdxgenClient {

    private static final String SBOM_JSON = "sbom.json";

    private final Map<String, String> cdxgenEnv;

    public CdxgenClient(
        @ConfigProperty(name = "github.token")
        String githubToken
    ) {
        // https://github.com/AppThreat/cdxgen#environment-variables
        this.cdxgenEnv = Map.of(
            "GITHUB_TOKEN", githubToken.trim()
        );
    }

    // option -r: Recurse mode suitable for mono-repos
    //            Used for projects containing multiple dependency files like pom.xml & yarn.lock
    private static final String CDXGEN_CMD = "cdxgen -r --fail-on-error -o " + SBOM_JSON;

    public CompletableFuture<SBOMGenerationResult> generateSBOM(File repoDir) {
        Function<ProcessHandler.ProcessResult, SBOMGenerationResult> mapResult = (ProcessHandler.ProcessResult processResult) -> {
            if (processResult instanceof ProcessHandler.ProcessResult.Success) {
                return readAndParseSBOM(new File(repoDir, SBOM_JSON));
            } else {
                var failure = (ProcessHandler.ProcessResult.Failure) processResult;
                return new SBOMGenerationResult.Failure("Command failed: " + CDXGEN_CMD, failure.cause());
            }
        };

        var future = ProcessHandler.run(CDXGEN_CMD, repoDir.getAbsoluteFile(), cdxgenEnv);
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
            final Bom sbom;
            var validationResult = jsonSBOMParser.validate(sbomContent);

            if (validationResult.isEmpty()) {
                sbom = jsonSBOMParser.parse(sbomContent);
            } else {
                return new SBOMGenerationResult.Failure("SBOM file is invalid", validationResult.stream().findFirst().get());
            }

            String group = null;
            String name = null;
            String version = null;
            if (sbom.getMetadata() != null && sbom.getMetadata().getComponent() != null) {
                group = sbom.getMetadata().getComponent().getGroup();
                name = sbom.getMetadata().getComponent().getName();
                version = sbom.getMetadata().getComponent().getVersion();
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

        record None() implements SBOMGenerationResult {
        }

        record Failure(
            String message,
            Throwable cause
        ) implements SBOMGenerationResult {
        }
    }

}
