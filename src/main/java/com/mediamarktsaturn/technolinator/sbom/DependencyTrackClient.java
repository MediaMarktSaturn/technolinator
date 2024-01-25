package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.cyclonedx.generators.json.BomJsonGenerator15;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.ExternalReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * API client for Dependency-Track
 */
@ApplicationScoped
public class DependencyTrackClient {

    private static final String API_PATH = "/api/v1", API_KEY = "X-API-Key";

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

    /**
     * Uploads the given  [sbom] to Dependency-Track, deactivating other versions of the same project if any
     */
    public Uni<Result<Project>> uploadSBOM(RepositoryDetails repoDetails, Bom sbom, String projectName, Project parentProject) {
        var projectVersion = repoDetails.version();
        var sbomBase64 = Base64.getEncoder().encodeToString(new BomJsonGenerator15(sbom).toJsonString().getBytes(StandardCharsets.UTF_8));
        var payload = new JsonObject(Map.of(
            "projectName", projectName,
            "projectVersion", projectVersion,
            "autoCreate", true,
            "bom", sbomBase64
        ));
        if (parentProject instanceof Project.Available a) {
            payload.put("parentUUID", a.projectId());
        }

        return client.putAbs(dtrackApiUrl + "/bom")
            .putHeader(API_KEY, dtrackApikey)
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
            .call(() -> deactivatePreviousVersion(projectName, projectVersion))
            .chain(i -> lookupProject(projectName, projectVersion))
            .call(r -> {
                if (r instanceof Result.Success<Project>(Project project) && project instanceof Project.Available p) {
                    Log.infof("Describe and tag project %s for %s", p.projectId(), projectName);
                    return tagAndDescribeAndActivateProject(p.projectId(), repoDetails);
                } else {
                    Log.infof("Cannot describe and tag project %s: %s", projectName, r);
                    return Uni.createFrom().voidItem();
                }
            })
            .onFailure().recoverWithItem(Result.Failure::new);
    }

    public Uni<Result<Project>> createOrUpdateParentProject(RepositoryDetails repoDetails) {
        var parentProject = createProjectBaseData(repoDetails);
        parentProject.put("name", repoDetails.name());
        parentProject.put("version", repoDetails.version());

        return client.putAbs(dtrackApiUrl + "/project")
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(parentProject)
            .chain(response -> {
                if (response.statusCode() == 201) {
                    var parentProjectId = response.bodyAsJsonObject().getString("uuid");
                    Log.infof("Created parent project %s named %s in version %s", parentProjectId, repoDetails.name(), repoDetails.version());
                    return Uni.createFrom().item(Result.success(new Project.Available("%s/projects/%s".formatted(dtrackBaseUrl, parentProjectId), parentProjectId)));
                } else if (response.statusCode() == 409) {
                    return lookupProject(repoDetails.name(), repoDetails.version())
                        .call(lookupResult -> {
                            if (lookupResult instanceof Result.Success<Project> lookedupProject) {
                                if (lookedupProject.result() instanceof Project.Available lookedupParent) {
                                    return tagAndDescribeAndActivateProject(lookedupParent.projectId(), repoDetails);
                                }
                            }
                            Log.errorf("Could not lookup parent project named %s in version %s", repoDetails.name(), repoDetails.version());
                            return Uni.createFrom().item(Result.failure(new Exception("Parent project could not be found")));
                        }).map(lookupResult -> {
                            if (lookupResult instanceof Result.Success<Project> success && success.result() instanceof Project.Available) {
                                return Result.success(success.result());
                            }
                            return Result.failure(new Exception("Parent project could not be found"));
                        });
                } else {
                    Log.errorf("Failed to create parent project named %s version %s: %s", repoDetails.name(), repoDetails.version(), response.bodyAsString());
                    return Uni.createFrom().item(Result.failure(new Exception("Failed to create parent project")));
                }
            });
    }

    Uni<Result<Project>> lookupProject(String projectName, String projectVersion) {
        return client.getAbs(dtrackApiUrl + "/project/lookup")
            .addQueryParam("name", projectName)
            .addQueryParam("version", projectVersion)
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to lookup project %s in version %s", projectName, projectVersion))
            .chain(response -> {
                if (response.statusCode() == 200) {
                    var projectUUID = response.bodyAsJsonObject().getString("uuid");
                    return Uni.createFrom().item(Result.success(Project.available("%s/projects/%s".formatted(dtrackBaseUrl, projectUUID), projectUUID)));
                } else {
                    Log.errorf("Could not get uuid for project %s in version %s, status: %s, message: %s", projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new Exception("Status " + response.statusCode()));
                }
            }).onFailure().recoverWithItem(Result.Failure::new);
    }

    Uni<Void> deactivatePreviousVersion(String projectName, String projectVersion) {
        return client.getAbs(dtrackApiUrl + "/project")
            .addQueryParam("name", projectName)
            .addQueryParam("excludeInactive", "true")
            .putHeader(API_KEY, dtrackApikey)
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
                            .toList()
                    ).discardItems();
                } else {
                    Log.errorf("Failed to deactivate previous versions of project %s in version %s, status: %s, message: %s", projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new Exception("Status " + response.statusCode()));
                }
            }).onFailure().recoverWithNull();
    }

    Uni<Void> deactivateProject(String projectUUID) {
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(JsonObject.of("active", false))
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.warnf(e, "Failed to disabled project %s", projectUUID))
            .onItem().invoke(() -> Log.infof("Disabled project %s", projectUUID))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    Uni<Void> tagAndDescribeAndActivateProject(String projectUUID, RepositoryDetails repoDetails) {
        var projectDetails = createProjectBaseData(repoDetails);
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(projectDetails)
            .onItem().invoke(() -> Log.infof("Updated projects %s metadata", projectUUID))
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.warnf(e, "Failed to update projects %s metadata", projectUUID))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    JsonObject createProjectBaseData(RepositoryDetails repoDetails) {
        var tagsArray = new JsonArray(repoDetails.topics().stream()
            .filter(t -> t != null && !t.isBlank())
            .map(tag -> JsonObject.of("name", tag)
            ).toList());
        var description = repoDetails.description();
        var descValue = description == null || description.isBlank() ? "" : description;
        var extRefs = JsonArray.of(
            JsonObject.of(
                "type", ExternalReference.Type.VCS.getTypeName(),
                "url", repoDetails.vcsUrl()
            ),
            JsonObject.of(
                "type", ExternalReference.Type.WEBSITE.getTypeName(),
                "url", repoDetails.websiteUrl()
            ),
            JsonObject.of(
                "type", ExternalReference.Type.RELEASE_NOTES.getTypeName(),
                "url", repoDetails.websiteUrl() + "/releases"
            )
        );
        return JsonObject.of(
            "tags", tagsArray,
            "description", descValue,
            "externalReferences", extRefs,
            "active", true
        );
    }

    boolean isDifferentVersionOfSameProject(JsonObject project, String projectName, String projectVersion) {
        return
            project.getString("name").equals(projectName)
                && !project.getString("version").equals(projectVersion);
    }

}
