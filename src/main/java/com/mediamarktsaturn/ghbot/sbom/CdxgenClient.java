package com.mediamarktsaturn.ghbot.sbom;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.ghbot.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

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

    private static final String CDXGEN_CMD = "cdxgen -o " + SBOM_JSON;

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
        if (sbomFile.exists() && sbomFile.canRead()) {
            try (var fis = new FileInputStream(sbomFile)) {
                var sbomJson = new JsonObject(Buffer.buffer(fis.readAllBytes()));
                return parseSBOM(sbomJson);
            } catch (Exception e) {
                return new SBOMGenerationResult.Failure("Failed to parse " + sbomFile.getAbsolutePath(), e);
            }
        } else {
            return new SBOMGenerationResult.Failure("Cannot read file " + sbomFile.getAbsolutePath(), null);
        }
    }

    private static SBOMGenerationResult parseSBOM(JsonObject sbom) {
        var metadata = sbom.getJsonObject("metadata");
        if (!sbom.getString("bomFormat").equals("CycloneDX") || metadata == null) {
            return new SBOMGenerationResult.Failure("Invalid sbom file format", null);
        }
        var component = metadata.getJsonObject("component");
        if (component != null) {
            var group = component.getString("group");
            var name = component.getString("name");
            var version = component.getString("version");

            if (group != null && name != null && version != null) {
                Log.infof("Proper SBOM file parsed; group: %s, name: %s, version: %s", group, name, version);
                return new SBOMGenerationResult.Proper(sbom, group, name, version);
            }
        }

        Log.info("SBOM file is missing component information");
        return new SBOMGenerationResult.Fallback(sbom);
    }

    public sealed interface SBOMGenerationResult {
        record Proper(
            JsonObject sbom,
            String group,
            String name,
            String version
        ) implements SBOMGenerationResult {
        }

        record Fallback(
            JsonObject sbom
        ) implements SBOMGenerationResult {
        }

        record Failure(
            String message,
            Throwable cause
        ) implements SBOMGenerationResult {
        }
    }

}
