package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.multipart.MultipartForm;
import jakarta.enterprise.context.ApplicationScoped;
import org.cyclonedx.Version;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.ExternalReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * API client for Dependency-Track
 */
@ApplicationScoped
public class DependencyTrackClient {

    private static final String API_KEY = "X-API-Key";
    private static final String API_DEPRECATED_HEADER = "X-API-Deprecated";
    private static final int PAGE_SIZE = 100;

    private final Set<String> reportedDeprecations = ConcurrentHashMap.newKeySet();
    private final WebClient client;
    private final String dtrackBaseUrl, dtrackApiUrl, dtrackApikey;

    public DependencyTrackClient(
        Vertx vertx,
        @ConfigProperty(name = "dtrack.apikey")
        String dtrackApikey,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl,
        @ConfigProperty(name = "dtrack.api_url")
        String dtrackApiUrl
    ) {
        this.client = WebClient.create(vertx);
        this.dtrackApikey = dtrackApikey.trim();
        this.dtrackBaseUrl = dtrackUrl.trim();
        this.dtrackApiUrl = dtrackApiUrl;
    }

    /**
     * Uploads the given  [sbom] to Dependency-Track, deactivating other versions of the same project if any
     */
    public Uni<Result<Project>> uploadSBOM(RepositoryDetails repoDetails, Bom sbom, String projectName, Project parentProject, Optional<String> commitSha) {
        var projectVersion = repoDetails.version();
        return Uni.createFrom().item(() -> {
                try {
                    return new BomJsonGenerator(sbom, Version.VERSION_17).toJsonString();
                } catch (GeneratorException e) {
                    throw new RuntimeException(e);
                }
            })
            .chain(payload ->
                client.postAbs(dtrackApiUrl + "/bom")
                    .putHeader(API_KEY, dtrackApikey)
                    .sendMultipartForm(MultipartForm.create()
                        .attribute("projectName", projectName)
                        .attribute("projectVersion", projectVersion)
                        .attribute("autoCreate", "true")
                        .attribute("isLatest", "true")
                        .attribute("bom", payload)
                    )
                    .onItem().invoke(deprecationCheck("POST /bom"))
                    .map(Unchecked.function(result -> {
                        if (result.statusCode() == 200) {
                            return result;
                        } else {
                            throw new DependencyTrackClientHttpException(result.statusCode(), result.bodyAsString());
                        }
                    }))
                    .onFailure().retry().atMost(3)
                    .onFailure().invoke(e -> Log.errorf(e, "Failed to upload project %s in version %s: %s", projectName, projectVersion, e.getMessage()))
                    .onItem().invoke(() -> Log.infof("Uploaded project %s in version %s", projectName, projectVersion))
                    .call(() -> deactivatePreviousVersion(projectName, projectVersion))
                    .chain(response -> resolveUploadedProject(response, projectName, projectVersion, commitSha))
                    .call(r -> {
                        if (r instanceof Result.Success<Project>(
                            Project project
                        ) && project instanceof Project.Available p) {
                            Log.infof("Describe and tag project %s for %s", p.projectId(), projectName);
                            return updateProjectMetaData(p.projectId(), repoDetails, parentProject, commitSha, Optional.empty());
                        } else {
                            Log.infof("Cannot describe and tag project %s: %s", projectName, r);
                            return Uni.createFrom().voidItem();
                        }
                    }))
            .onFailure().recoverWithItem(Result::failure);
    }

    /**
     * The BOM upload response contains the uuid of the project the BOM was assigned to, so there's no need to look it up.
     * Falls back to a lookup by name and version if the response doesn't contain it.
     */
    private Uni<Result<Project>> resolveUploadedProject(HttpResponse<?> uploadResponse, String projectName, String projectVersion, Optional<String> commitSha) {
        return parseProjectUuid(uploadResponse)
            .map(projectUuid -> Uni.createFrom().item(Result.success(
                Project.available("%s/projects/%s".formatted(dtrackBaseUrl, projectUuid), projectUuid, commitSha.map(DependencyTrackClient::shortCommitSha)))))
            .orElseGet(() -> {
                Log.debugf("Upload response for project %s in version %s doesn't contain a project uuid, looking it up", projectName, projectVersion);
                return lookupProject(projectName, projectVersion);
            });
    }

    private static Optional<String> parseProjectUuid(HttpResponse<?> uploadResponse) {
        try {
            return Optional.ofNullable(uploadResponse.bodyAsJsonObject())
                .map(body -> body.getString("projectUuid"))
                .filter(uuid -> !uuid.isBlank());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Logs a warning once per endpoint if Dependency-Track flags it as deprecated
     */
    private <T> Consumer<HttpResponse<T>> deprecationCheck(String endpoint) {
        return response -> {
            if ("true".equalsIgnoreCase(response.getHeader(API_DEPRECATED_HEADER)) && reportedDeprecations.add(endpoint)) {
                Log.warnf("Dependency-Track flags endpoint '%s' as deprecated (%s: true), consider migrating to its successor", endpoint, API_DEPRECATED_HEADER);
            }
        };
    }

    public Uni<Result<Project>> createOrUpdateParentProject(RepositoryDetails repoDetails, Optional<String> commitSha) {
        var parentProject = createProjectBaseData(repoDetails, commitSha, Optional.empty());
        parentProject.put("name", repoDetails.name());
        parentProject.put("version", repoDetails.version());

        return client.putAbs(dtrackApiUrl + "/project")
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(parentProject)
            .onItem().invoke(deprecationCheck("PUT /project"))
            .chain(response -> {
                if (response.statusCode() == 201) {
                    var projectJson = response.bodyAsJsonObject();
                    var parentProjectId = projectJson.getString("uuid");
                    var projectCommitSha = parseCommitShaFromDescription(projectJson.getString("description"));
                    Log.infof("Created parent project %s named %s in version %s", parentProjectId, repoDetails.name(), repoDetails.version());
                    return Uni.createFrom().item(Result.success(Project.available("%s/projects/%s".formatted(dtrackBaseUrl, parentProjectId), parentProjectId, projectCommitSha)));
                } else if (response.statusCode() == 409) {
                    return lookupProject(repoDetails.name(), repoDetails.version())
                        .call(lookupResult -> {
                            if (lookupResult instanceof Result.Success<Project> lookedupProject) {
                                if (lookedupProject.result() instanceof Project.Available lookedupParent) {
                                    return updateProjectMetaData(lookedupParent.projectId(), repoDetails, Project.none(), commitSha, Optional.empty());
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

    /**
     * Appends the given version to the projects description if it matches the given commitSha
     */
    public Uni<Result<Project>> appendVersionInfo(RepositoryDetails repoDetails, String version, Optional<String> commitSha) {
        if (commitSha.isEmpty()) {
            return Uni.createFrom().item(Result.success(Project.none()));
        }
        return lookupProject(repoDetails.name(), repoDetails.version())
            .chain(result -> {
                if (result instanceof Result.Success<Project> p && p.result() instanceof Project.Available a) {
                    if (a.commitSha().isEmpty()) {
                        Log.warnf("Project %s does not have commit information available", a.projectId());
                    } else if (!commitSha.get().startsWith(a.commitSha().get())) {
                        Log.warnf("Projects %s commit %s doesn't match versions %s commit %s", a.projectId(), a.commitSha().get(), version, commitSha.get());
                    } else {
                        // commit hashes match, let's update the project with the given version
                        return updateProjectMetaData(a.projectId(), repoDetails, Project.none(), commitSha, Optional.of(version))
                            .replaceWith(result);
                    }
                }
                return Uni.createFrom().item(Result.success(Project.none()));
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
            .onItem().invoke(deprecationCheck("GET /project/lookup"))
            .chain(response -> {
                if (response.statusCode() == 200) {
                    var projectJson = response.bodyAsJsonObject();
                    var projectUUID = projectJson.getString("uuid");
                    var projectCommitSha = parseCommitShaFromDescription(projectJson.getString("description"));
                    return Uni.createFrom().item(Result.success(Project.available("%s/projects/%s".formatted(dtrackBaseUrl, projectUUID), projectUUID, projectCommitSha)));
                } else {
                    Log.errorf("Could not get uuid for project %s in version %s, status: %s, message: %s", projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new Exception("Status " + response.statusCode()));
                }
            }).onFailure().recoverWithItem(Result.Failure::new);
    }

    Uni<Void> deactivatePreviousVersion(String projectName, String projectVersion) {
        // the concise representation is sufficient here: only uuid, name and version are required, name has to match exactly
        return client.getAbs(dtrackApiUrl + "/project/concise")
            .addQueryParam("name", projectName)
            .addQueryParam("active", "true")
            .addQueryParam("pageSize", String.valueOf(PAGE_SIZE))
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to list projects named %s", projectName))
            .onItem().invoke(deprecationCheck("GET /project/concise"))
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

    /**
     * Archives (deactivates) all active projects in all versions belonging to the repository identified by
     * the given name and URLs, including multi-module children and parent projects.
     *
     * @return the number of successfully archived projects
     */
    public Uni<Integer> archiveProjectsOfRepository(String repoName, String vcsUrl, String websiteUrl) {
        return listProjectsBySearchText(repoName, 1, new ArrayList<>())
            .chain(candidates -> {
                var matching = candidates.stream()
                    .filter(p -> belongsToRepository(p, repoName, vcsUrl, websiteUrl))
                    .toList();

                if (matching.isEmpty()) {
                    var nameMatches = candidates.stream().filter(p -> matchesRepoName(p, repoName)).count();
                    if (nameMatches > 0) {
                        Log.warnf("Found %d project(s) matching the name of repo %s, but none referencing %s or %s; " +
                            "not archiving anything", nameMatches, repoName, vcsUrl, websiteUrl);
                    } else {
                        Log.infof("No active projects found for repo %s", repoName);
                    }
                    return Uni.createFrom().item(0);
                }

                // dependency-track rejects deactivating a parent having active children, so children go first
                var children = matching.stream().filter(p -> isChildProject(p, repoName)).toList();
                var parents = matching.stream().filter(p -> !isChildProject(p, repoName)).toList();
                Log.infof("Archiving %d project(s) of repo %s (%d children, %d others)",
                    matching.size(), repoName, children.size(), parents.size());

                return deactivateProjects(children)
                    .chain(archivedChildren -> deactivateProjects(parents).map(archivedParents -> archivedChildren + archivedParents))
                    .invoke(archived -> Log.infof("Archived %d of %d project(s) of repo %s", archived, matching.size(), repoName));
            });
    }

    private Uni<List<JsonObject>> listProjectsBySearchText(String searchText, int pageNumber, List<JsonObject> collected) {
        return client.getAbs(dtrackApiUrl + "/project")
            .addQueryParam("searchText", searchText)
            .addQueryParam("excludeInactive", "true")
            .addQueryParam("pageSize", String.valueOf(PAGE_SIZE))
            .addQueryParam("pageNumber", String.valueOf(pageNumber))
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to list projects matching %s", searchText))
            .onItem().invoke(deprecationCheck("GET /project"))
            .chain(response -> {
                if (response.statusCode() != 200) {
                    Log.errorf("Failed to list projects matching %s, status: %s, message: %s", searchText, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().<List<JsonObject>>failure(new DependencyTrackClientHttpException(response.statusCode(), response.bodyAsString()));
                }
                var page = response.bodyAsJsonArray();
                for (int i = 0; i < page.size(); i++) {
                    collected.add(page.getJsonObject(i));
                }

                var totalCount = Optional.ofNullable(response.getHeader("X-Total-Count")).map(String::trim).flatMap(DependencyTrackClient::parseInt);
                var lastPage = page.size() < PAGE_SIZE || totalCount.map(total -> pageNumber * PAGE_SIZE >= total).orElse(false);
                if (lastPage) {
                    return Uni.createFrom().item((List<JsonObject>) collected);
                }
                return listProjectsBySearchText(searchText, pageNumber + 1, collected);
            });
    }

    private Uni<Integer> deactivateProjects(List<JsonObject> projects) {
        if (projects.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        return Uni.combine().all().unis(
            projects.stream().map(p -> deactivateProject(p.getString("uuid"))).toList()
        ).with(Boolean.class, results -> (int) results.stream().filter(Boolean::booleanValue).count());
    }

    private static Optional<Integer> parseInt(String value) {
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean matchesRepoName(JsonObject project, String repoName) {
        var name = project.getString("name");
        return name != null && (name.equals(repoName) || name.startsWith(repoName + "-"));
    }

    boolean belongsToRepository(JsonObject project, String repoName, String vcsUrl, String websiteUrl) {
        if (!matchesRepoName(project, repoName)) {
            return false;
        }
        var extRefs = project.getJsonArray("externalReferences");
        if (extRefs == null) {
            return false;
        }
        for (int i = 0; i < extRefs.size(); i++) {
            var url = extRefs.getJsonObject(i).getString("url");
            if (url != null && (url.equalsIgnoreCase(vcsUrl) || url.equalsIgnoreCase(websiteUrl))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChildProject(JsonObject project, String repoName) {
        return project.getJsonObject("parent") != null || !repoName.equals(project.getString("name"));
    }

    /**
     * @return true if the project was deactivated successfully
     */
    Uni<Boolean> deactivateProject(String projectUUID) {
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(JsonObject.of("active", false))
            .onFailure().retry().atMost(3)
            .onItem().invoke(deprecationCheck("PATCH /project/{uuid}"))
            .map(response -> {
                if (response.statusCode() == 200) {
                    Log.infof("Disabled project %s", projectUUID);
                    return true;
                }
                Log.warnf("Failed to disable project %s with %s: %s", projectUUID, response.statusCode(), response.bodyAsString());
                return false;
            })
            .onFailure().invoke(e -> Log.warnf(e, "Failed to disable project %s", projectUUID))
            .onFailure().recoverWithItem(false);
    }

    Uni<Void> updateProjectMetaData(String projectUUID, RepositoryDetails repoDetails, Project parentProject, Optional<String> commitSha, Optional<String> version) {
        var projectDetails = createProjectBaseData(repoDetails, commitSha, version);
        if (parentProject instanceof Project.Available parent) {
            projectDetails.put("parent", JsonObject.of("uuid", parent.projectId()));
        }
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(projectDetails)
            .onItem().invoke(deprecationCheck("PATCH /project/{uuid}"))
            .onItem().invoke(response -> {
                if (response.statusCode() == 200) {
                    Log.infof("Updated projects %s metadata", projectUUID);
                } else {
                    Log.warnf("Failed to update projects %s metadata with %s: %s", projectUUID, response.statusCode(), response.bodyAsString());
                }
            })
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.warnf(e, "Failed to update projects %s metadata", projectUUID))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    JsonObject createProjectBaseData(RepositoryDetails repoDetails, Optional<String> commitSha, Optional<String> version) {
        var tagsArray = new JsonArray(repoDetails.topics().stream()
            .filter(t -> t != null && !t.isBlank())
            .map(tag -> JsonObject.of("name", tag)
            ).toList());
        // prefix description with #commitSha# to allow VCS matching
        var shaDesc = createCommitShaDescriptionPrefix(commitSha);
        var shaAndVersionDesc = shaDesc + version.map(v -> v + " | ").orElse("");
        var description = Optional.ofNullable(repoDetails.description()).map(String::trim).map(shaAndVersionDesc::concat).orElse(shaAndVersionDesc);
        // dtrack allows a max of 255 chars for description
        var descValue = description.length() < 255 ? description : (description.substring(0, 251) + "...");
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

    /**
     * Build the prefix for a projects description in form:
     * '#7charShortCommitSha# ' or '' if there's no commitSha available
     */
    String createCommitShaDescriptionPrefix(Optional<String> commitSha) {
        return commitSha.map(DependencyTrackClient::shortCommitSha).map(sha -> "#" + sha + "# ").orElse("");
    }

    private static String shortCommitSha(String commitSha) {
        return commitSha.substring(0, Integer.min(7, commitSha.length()));
    }

    /**
     * Extracts the commit hash from a given project description prefixed by {@link #createCommitShaDescriptionPrefix(Optional)}
     */
    Optional<String> parseCommitShaFromDescription(String description) {
        if (description != null && description.matches("^#\\w{1,7}#.*")) {
            var desc = description.substring(1);
            desc = desc.substring(0, desc.indexOf('#'));
            return Optional.of(desc);
        } else {
            return Optional.empty();
        }
    }

    boolean isDifferentVersionOfSameProject(JsonObject project, String projectName, String projectVersion) {
        return
            Objects.equals(project.getString("name"), projectName)
                && !Objects.equals(project.getString("version"), projectVersion);
    }

}
