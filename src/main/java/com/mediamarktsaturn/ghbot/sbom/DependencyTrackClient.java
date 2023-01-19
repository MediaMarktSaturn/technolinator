package com.mediamarktsaturn.ghbot.sbom;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import org.cyclonedx.generators.json.BomJsonGenerator14;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;

@ApplicationScoped
public class DependencyTrackClient {

    private final static String API_PATH = "/api/v1";

    private final WebClient client;
    private final String dtrackBaseUrl, dtrackApiUrl, dtrackApikey;

    public DependencyTrackClient(
        Vertx vertx,
        @ConfigProperty(name = "dtrack.apikey")
        String dtrackApikey,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl
    ) {
        this.client = WebClient.create(vertx);
        this.dtrackApikey = dtrackApikey.trim();
        this.dtrackBaseUrl = dtrackUrl.trim();
        this.dtrackApiUrl = dtrackBaseUrl + API_PATH;
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

        return client.putAbs(dtrackApiUrl + "/bom")
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
            .chain(i -> getCurrentVersionUrl(projectName, projectVersion))
            .onFailure().recoverWithItem(e -> (UploadResult) new UploadResult.Failure(dtrackBaseUrl, e))
            .subscribeAsCompletionStage();
    }

    Uni<UploadResult> getCurrentVersionUrl(String projectName, String projectVersion) {
        return client.getAbs(dtrackApiUrl + "/project/lookup")
            .addQueryParam("name", projectName)
            .addQueryParam("version", projectVersion)
            .putHeader("X-API-Key", dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to lookup project %s in version %s", projectName, projectVersion))
            .chain(response -> {
                if (response.statusCode() == 200) {
                    var projectUUID = response.bodyAsJsonObject().getString("uuid");
                    return Uni.createFrom().item((UploadResult) new UploadResult.Success("%s/projects/%s".formatted(dtrackBaseUrl, projectUUID)));
                } else {
                    Log.errorf("Failed to deactivate previous versions of project %s in version %s, status: %s, message: %s", projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new Exception("Status " + response.statusCode()));
                }
            }).onFailure().recoverWithItem(failure -> (UploadResult) new UploadResult.Failure(dtrackBaseUrl, failure));
    }

    Uni<Void> deactivatePreviousVersion(String projectName, String projectVersion) {
        return client.getAbs(dtrackApiUrl + "/project")
            .addQueryParam("name", projectName)
            .addQueryParam("excludeInactive", "true")
            .putHeader("X-API-Key", dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to list projects named %s", projectName))
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
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
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
        record Success(
            String projectUrl
        ) implements UploadResult {
        }

        record None(
        ) implements UploadResult {
        }

        record Failure(
            String baseUrl,
            Throwable cause
        ) implements UploadResult {
        }
    }

}
