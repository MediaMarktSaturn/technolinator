package com.mediamarktsaturn.ghbot.sbom;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.cyclonedx.generators.json.BomJsonGenerator14;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DependencyTrackClient {

    private final static String API_PATH = "/api/v1";

    private final WebClient client;
    private final String dtrackUrl, dtrackApikey;

    public DependencyTrackClient(
        Vertx vertx,
        @ConfigProperty(name = "dtrack.apikey")
        String dtrackApikey,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl
    ) {
        this.client = WebClient.create(vertx);
        this.dtrackApikey = dtrackApikey.trim();
        this.dtrackUrl = dtrackUrl.trim() + API_PATH;
    }

    public CompletableFuture<UploadResult> uploadSBOM(String projectName, String projectVersion, Bom sbom) {
        var objectMapper = new ObjectMapper();

        var sbomBase64 = Base64.getEncoder().encodeToString(new BomJsonGenerator14(sbom).toJsonString().getBytes(StandardCharsets.UTF_8));
        var payload = new JsonObject(Map.of(
            "projectName", projectName,
            "projectVersion", projectVersion,
            "autoCreate", true,
            "bom", sbomBase64
        ));

        return client.putAbs(dtrackUrl + "/bom")
            .putHeader("X-API-Key", dtrackApikey)
            .sendJsonObject(payload)
            .map(Unchecked.function(result -> {
                if (result.statusCode() == 200) {
                    return result;
                } else {
                    throw new Exception("Status " + result.statusCode());
                }
            }))
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to upload project %s in version %s", projectName, projectVersion))
            .onItem().invoke(() -> Log.infof("Uploaded project %s in version %s", projectName, projectVersion))
            .chain(() -> deactivatePreviousVersion(projectName, projectVersion))
            .onItem().transform(i -> (UploadResult) new UploadResult.Success())
            .onFailure().recoverWithItem(e -> (UploadResult) new UploadResult.Failure(e))
            .subscribeAsCompletionStage();
    }

    Uni<Void> deactivatePreviousVersion(String projectName, String projectVersion) {
        return client.getAbs(dtrackUrl + "/project")
            .addQueryParam("name", projectName)
            .addQueryParam("excludeInactive", "true")
            .putHeader("X-API-Key", dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to list projects named %", projectName))
            .chain(response -> {
                if (response.statusCode() == 200) {
                    return Uni.combine().all().unis(
                        response.bodyAsJsonArray().stream()
                            .filter(o -> o instanceof JsonObject && isDifferentVersionOfSameProject((JsonObject) o, projectName, projectVersion))
                            .map(p -> ((JsonObject) p).getString("uuid"))
                            .map(this::deactivateProject)
                            .collect(Collectors.toList())
                    ).discardItems();
                } else {
                    Log.errorf("Failed to deactivate previous versions of project %s in version %s, status: %s, message: %s", projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new Exception("Status " + response.statusCode()));
                }
            }).onFailure().recoverWithNull();
    }

    Uni<Void> deactivateProject(String projectUUID) {
        return client.patchAbs(dtrackUrl + "/project/" + projectUUID)
            .putHeader("X-API-Key", dtrackApikey)
            .sendJsonObject(JsonObject.of("active", false))
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.warnf(e, "Failed to disabled project %s", projectUUID))
            .onItem().invoke(() -> Log.infof("Disabled project %s", projectUUID))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    boolean isDifferentVersionOfSameProject(JsonObject project, String projectName, String projectVersion) {
        return
            project.getString("name").equals(projectName)
                && !project.getString("version").equals(projectVersion);
    }

    public sealed interface UploadResult {
        record Success() implements UploadResult {
        }

        record Failure(
            Throwable cause
        ) implements UploadResult {
        }
    }

}
