package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * API client for Dependency-Track
 */
@ApplicationScoped
public class DependencyTrackClient {

    private static final String API_KEY = "X-API-Key";
    private static final String API_DEPRECATED_HEADER = "X-API-Deprecated";
    private static final int PAGE_SIZE = 100;
    private static final int METRICS_CONCURRENCY = 4;

    private final Set<String> reportedDeprecations = ConcurrentHashMap.newKeySet();
    private final WebClient client;
    private final String dtrackBaseUrl, dtrackApiUrl, dtrackApikey;
    private final Duration bomProcessingTimeout, pollInterval;

    public DependencyTrackClient(
        Vertx vertx,
        @ConfigProperty(name = "dtrack.apikey")
        String dtrackApikey,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl,
        @ConfigProperty(name = "dtrack.api_url")
        String dtrackApiUrl,
        @ConfigProperty(name = "dtrack.bom_processing.timeout_seconds", defaultValue = "300")
        long bomProcessingTimeoutSeconds,
        @ConfigProperty(name = "dtrack.bom_processing.poll_interval_millis", defaultValue = "2000")
        long pollIntervalMillis
    ) {
        this.bomProcessingTimeout = Duration.ofSeconds(bomProcessingTimeoutSeconds);
        this.pollInterval = Duration.ofMillis(pollIntervalMillis);
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
                    .onFailure(DependencyTrackClient::isRetryable).retry().atMost(3)
                    .onFailure().invoke(e -> Log.errorf(e, "Failed to upload project %s in version %s: %s", projectName, projectVersion, e.getMessage()))
                    .onItem().invoke(() -> Log.infof("Uploaded project %s in version %s", projectName, projectVersion))
                    .call(() -> deactivatePreviousVersion(projectName, projectVersion))
                    .call(response -> awaitBomProcessing(parseToken(response), projectName, projectVersion))
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
     * Transport failures and server side errors are worth another attempt, rejections of the request itself are not.
     */
    private static boolean isRetryable(Throwable failure) {
        return !(failure instanceof DependencyTrackClientHttpException e)
            || e.getHttpStatus() >= 500 || e.getHttpStatus() == 408 || e.getHttpStatus() == 429;
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

    private static Optional<String> parseToken(HttpResponse<?> uploadResponse) {
        try {
            return Optional.ofNullable(uploadResponse.bodyAsJsonObject())
                .map(body -> body.getString("token"))
                .filter(token -> !token.isBlank());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Dependency-Track processes uploaded BOMs asynchronously and, while doing so, overwrites metadata of the project
     * like external references and tags. Updating the metadata before the processing finished therefore gets lost silently.
     * Waits (bounded) until the processing of the upload identified by the given token is done.
     * Never fails, if the state can't be determined, the caller continues anyway.
     */
    private Uni<Void> awaitBomProcessing(Optional<String> token, String projectName, String projectVersion) {
        if (token.isEmpty()) {
            Log.warnf("Upload response for project %s in version %s doesn't contain a processing token, cannot wait for BOM processing", projectName, projectVersion);
            return Uni.createFrom().voidItem();
        }
        var deadline = System.nanoTime() + bomProcessingTimeout.toNanos();
        return pollBomProcessing(token.get(), projectName, projectVersion, deadline);
    }

    private Uni<Void> pollBomProcessing(String token, String projectName, String projectVersion, long deadlineNanos) {
        return client.getAbs(dtrackApiUrl + "/event/token/" + token)
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onItem().invoke(deprecationCheck("GET /event/token/{uuid}"))
            .chain(response -> {
                if (response.statusCode() != 200) {
                    Log.warnf("Failed to query BOM processing state of project %s in version %s with %s: %s",
                        projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().voidItem();
                }
                if (!isProcessing(response)) {
                    Log.debugf("BOM processing of project %s in version %s finished", projectName, projectVersion);
                    return Uni.createFrom().voidItem();
                }
                if (System.nanoTime() >= deadlineNanos) {
                    Log.warnf("BOM of project %s in version %s is still processing after %s, continuing anyway. Metadata might get overwritten by Dependency-Track.",
                        projectName, projectVersion, bomProcessingTimeout);
                    return Uni.createFrom().voidItem();
                }
                return Uni.createFrom().voidItem()
                    .onItem().delayIt().by(pollInterval)
                    .chain(() -> pollBomProcessing(token, projectName, projectVersion, deadlineNanos));
            })
            .onFailure().invoke(e -> Log.warnf(e, "Failed to query BOM processing state of project %s in version %s, continuing anyway", projectName, projectVersion))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    private static boolean isProcessing(HttpResponse<?> response) {
        try {
            return Optional.ofNullable(response.bodyAsJsonObject())
                .map(body -> body.getBoolean("processing", false))
                .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
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
            .<Result<Project>>chain(response -> {
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
            })
            .onFailure().recoverWithItem(failure -> {
                Log.errorf(failure, "Failed to create or update parent project named %s version %s", repoDetails.name(), repoDetails.version());
                return Result.<Project>failure(failure);
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
        return listProjectsBySearchText(repoName, true, 1, new ArrayList<>())
            .chain(candidates -> {
                var matching = selectProjectsOfRepository(candidates, repoName, vcsUrl, websiteUrl);

                if (matching.isEmpty()) {
                    var nameMatches = candidates.stream().filter(p -> matchesRepoName(p, repoName)).count();
                    if (nameMatches > 0) {
                        Log.warnf("Found %d project(s) matching the name of repo %s, but all reference other repositories than %s or %s; " +
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

    /**
     * Outcome of {@link #renameProjectsOfRepository}
     *
     * @param renamed  projects renamed successfully
     * @param archived projects that could not be renamed (e.g. name and version already taken) and got archived instead
     * @param failed   projects that could neither be renamed nor archived
     */
    public record RenameResult(int renamed, int archived, int failed) {
    }

    /**
     * Renames all projects (in all versions, active or not) belonging to the repository identified by the old name and URLs
     * to the new repository name, including multi-module children (named '[repoName]-[module]') and parent projects.
     * The repository references of the projects are updated to the new URLs as well.
     * Projects that can't be renamed, e.g. because the new name is already taken in the same version, get archived.
     * A new project is created with the next analysis of the repository as usual.
     */
    public Uni<RenameResult> renameProjectsOfRepository(String oldName, String newName,
                                                        String oldVcsUrl, String oldWebsiteUrl,
                                                        String newVcsUrl, String newWebsiteUrl) {
        return listProjectsBySearchText(oldName, false, 1, new ArrayList<>())
            .chain(candidates -> {
                var matching = selectProjectsOfRepository(candidates, oldName, oldVcsUrl, oldWebsiteUrl);

                if (matching.isEmpty()) {
                    Log.infof("No projects found for renamed repo %s (now %s)", oldName, newName);
                    return Uni.createFrom().item(new RenameResult(0, 0, 0));
                }

                Log.infof("Renaming %d project(s) of repo %s to %s", matching.size(), oldName, newName);
                return Uni.combine().all().unis(
                    matching.stream()
                        .map(p -> renameProject(p, oldName, newName, oldVcsUrl, oldWebsiteUrl, newVcsUrl, newWebsiteUrl))
                        .toList()
                ).with(Boolean.class, results -> {
                    var notRenamed = new ArrayList<JsonObject>();
                    for (int i = 0; i < results.size(); i++) {
                        if (!results.get(i)) {
                            notRenamed.add(matching.get(i));
                        }
                    }
                    return new RenameOutcome(matching.size() - notRenamed.size(), notRenamed);
                }).chain(outcome -> archiveNotRenamed(outcome, oldName, newName));
            });
    }

    private record RenameOutcome(int renamed, List<JsonObject> notRenamed) {
    }

    private Uni<RenameResult> archiveNotRenamed(RenameOutcome outcome, String oldName, String newName) {
        // inactive projects are archived already, nothing to do for them
        var toArchive = outcome.notRenamed().stream().filter(p -> p.getBoolean("active", true)).toList();
        if (!outcome.notRenamed().isEmpty()) {
            Log.warnf("%d project(s) of repo %s couldn't be renamed to %s, archiving %d of them instead",
                outcome.notRenamed().size(), oldName, newName, toArchive.size());
        }
        var children = toArchive.stream().filter(p -> isChildProject(p, oldName)).toList();
        var parents = toArchive.stream().filter(p -> !isChildProject(p, oldName)).toList();
        return deactivateProjects(children)
            .chain(archivedChildren -> deactivateProjects(parents).map(archivedParents -> archivedChildren + archivedParents))
            .map(archived -> new RenameResult(outcome.renamed(), archived, outcome.notRenamed().size() - archived))
            .invoke(result -> Log.infof("Renamed %d, archived %d, failed %d project(s) of renamed repo %s (now %s)",
                result.renamed(), result.archived(), result.failed(), oldName, newName));
    }

    /**
     * @return true if the project was renamed successfully
     */
    Uni<Boolean> renameProject(JsonObject project, String oldName, String newName,
                               String oldVcsUrl, String oldWebsiteUrl, String newVcsUrl, String newWebsiteUrl) {
        return renameProjectWithStatus(project, oldName, newName, oldVcsUrl, oldWebsiteUrl, newVcsUrl, newWebsiteUrl)
            .map(status -> status == RenameStatus.RENAMED || status == RenameStatus.UNCHANGED);
    }

    /**
     * Outcome of renaming a single project
     */
    public enum RenameStatus {
        /**
         * The project was renamed and/or its repository references were updated
         */
        RENAMED,
        /**
         * The project neither has a name starting with the old repository name nor references to the old repository, so nothing was changed
         */
        UNCHANGED,
        /**
         * The new name is taken already in the version of the project
         */
        CONFLICT,
        /**
         * The project couldn't be changed for another reason
         */
        FAILED
    }

    /**
     * Renames a single project of a repository that was renamed, the way {@link #renameProjectsOfRepository} does: its name, if it
     * starts with the old name of the repository, and the references to the old repository.
     * Doesn't archive the project if that isn't possible, which is up to the caller.
     */
    public Uni<RenameStatus> renameProjectOfRepository(DependencyTrackProject project, String oldName, String newName,
                                                       String oldVcsUrl, String oldWebsiteUrl, String newVcsUrl, String newWebsiteUrl) {
        var json = JsonObject.of(
            "uuid", project.uuid(),
            "name", project.name(),
            "externalReferences", new JsonArray(project.externalReferences().stream().map(DependencyTrackProject.Reference::toJson).toList())
        );
        return renameProjectWithStatus(json, oldName, newName, oldVcsUrl, oldWebsiteUrl, newVcsUrl, newWebsiteUrl);
    }

    private Uni<RenameStatus> renameProjectWithStatus(JsonObject project, String oldName, String newName,
                                                      String oldVcsUrl, String oldWebsiteUrl, String newVcsUrl, String newWebsiteUrl) {
        var projectUUID = project.getString("uuid");
        var currentName = project.getString("name");
        var targetName = currentName.regionMatches(true, 0, oldName, 0, oldName.length())
            ? newName + currentName.substring(oldName.length())
            : currentName;

        var update = JsonObject.of("name", targetName);
        var currentReferences = project.getJsonArray("externalReferences");
        var externalReferences = renameExternalReferences(currentReferences, oldVcsUrl, oldWebsiteUrl, newVcsUrl, newWebsiteUrl);
        if (externalReferences != null) {
            update.put("externalReferences", externalReferences);
        }
        if (targetName.equals(currentName) && (externalReferences == null || externalReferences.equals(currentReferences))) {
            return Uni.createFrom().item(RenameStatus.UNCHANGED);
        }

        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(update)
            .onFailure().retry().atMost(3)
            .onItem().invoke(deprecationCheck("PATCH /project/{uuid}"))
            .map(response -> {
                if (response.statusCode() == 200) {
                    Log.infof("Renamed project %s from %s to %s", projectUUID, currentName, targetName);
                    return RenameStatus.RENAMED;
                }
                if (response.statusCode() == 409) {
                    Log.warnf("Cannot rename project %s from %s to %s, name and version are already taken", projectUUID, currentName, targetName);
                    return RenameStatus.CONFLICT;
                }
                Log.warnf("Failed to rename project %s from %s to %s with %s: %s", projectUUID, currentName, targetName, response.statusCode(), response.bodyAsString());
                return RenameStatus.FAILED;
            })
            .onFailure().invoke(e -> Log.warnf(e, "Failed to rename project %s from %s to %s", projectUUID, currentName, targetName))
            .onFailure().recoverWithItem(RenameStatus.FAILED);
    }

    /**
     * Replaces the old repository URLs by the new ones in the given external references, keeping everything else.
     * The website URL is replaced as prefix to cover derived URLs like the release-notes one.
     *
     * @return the updated references, null if there are none
     */
    JsonArray renameExternalReferences(JsonArray externalReferences, String oldVcsUrl, String oldWebsiteUrl, String newVcsUrl, String newWebsiteUrl) {
        if (externalReferences == null || externalReferences.isEmpty()) {
            return null;
        }
        var result = new JsonArray();
        for (int i = 0; i < externalReferences.size(); i++) {
            var ref = externalReferences.getJsonObject(i).copy();
            var url = ref.getString("url");
            if (url != null) {
                if (url.equalsIgnoreCase(oldVcsUrl)) {
                    ref.put("url", newVcsUrl);
                } else if (url.regionMatches(true, 0, oldWebsiteUrl, 0, oldWebsiteUrl.length())
                    && (url.length() == oldWebsiteUrl.length() || url.charAt(oldWebsiteUrl.length()) == '/')) {
                    ref.put("url", newWebsiteUrl + url.substring(oldWebsiteUrl.length()));
                }
            }
            result.add(ref);
        }
        return result;
    }

    /**
     * Lists all projects, in contrast to {@link #listProjectsBySearchText} not restricted to the ones matching a name.
     * <p>
     * Dependency-Track sorts the projects by name and version, and the result is collected completely before it's returned,
     * so it's safe to change the listed projects (e.g. archive them) while processing it.
     * Fails if the projects can't be listed completely.
     *
     * @param excludeInactive Skip archived projects
     */
    public Uni<List<DependencyTrackProject>> listProjects(boolean excludeInactive) {
        return listPages("/project", null, excludeInactive, 1, new ArrayList<>())
            .map(DependencyTrackClient::uniqueProjects);
    }

    /**
     * Archives (deactivates) the project identified by [projectUuid].
     * Dependency-Track rejects archiving a parent having active children, so those need to be archived first.
     *
     * @return true if the project was archived successfully
     */
    public Uni<Boolean> archiveProject(String projectUuid) {
        return deactivateProject(projectUuid);
    }

    /**
     * Reactivates the project identified by [projectUuid], the opposite of {@link #archiveProject}.
     * Only the state is changed, anything else of the project stays as it is.
     *
     * @return true if the project was reactivated successfully
     */
    public Uni<Boolean> reactivateProject(String projectUuid) {
        return changeProjectState(projectUuid, true);
    }

    /**
     * Fetches a single project in any state.
     * Fails if the project can't be fetched, but not if it doesn't exist.
     */
    public Uni<Optional<DependencyTrackProject>> getProject(String projectUuid) {
        return client.getAbs(dtrackApiUrl + "/project/" + projectUuid)
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to get project %s", projectUuid))
            .onItem().invoke(deprecationCheck("GET /project/{uuid}"))
            .chain(response -> {
                if (response.statusCode() == 404) {
                    return Uni.createFrom().item(Optional.<DependencyTrackProject>empty());
                }
                if (response.statusCode() != 200) {
                    Log.errorf("Failed to get project %s, status: %s, message: %s", projectUuid, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new DependencyTrackClientHttpException(response.statusCode(), response.bodyAsString()));
                }
                return Uni.createFrom().item(Optional.of(DependencyTrackProject.fromJson(response.bodyAsJsonObject())));
            });
    }

    /**
     * Lists the projects in any version and state, whose name contains [searchText] (case-insensitive, as Dependency-Track does the search),
     * so the result might contain projects not named exactly like it.
     * Fails if the projects can't be listed completely.
     */
    public Uni<List<DependencyTrackProject>> searchProjects(String searchText) {
        return listPages("/project", searchText, false, 1, new ArrayList<>()).map(DependencyTrackClient::uniqueProjects);
    }

    /**
     * Fetches the current metrics Dependency-Track calculated for a project.
     * Fails if they can't be fetched, but not if there are none (yet), or the project doesn't exist.
     */
    public Uni<Optional<ProjectMetrics>> getProjectMetrics(String projectUuid) {
        return client.getAbs(dtrackApiUrl + "/metrics/project/" + projectUuid + "/current")
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to get metrics of project %s", projectUuid))
            .onItem().invoke(deprecationCheck("GET /metrics/project/{uuid}/current"))
            .chain(response -> {
                if (response.statusCode() == 404) {
                    return Uni.createFrom().item(Optional.<ProjectMetrics>empty());
                }
                if (response.statusCode() != 200) {
                    Log.errorf("Failed to get metrics of project %s, status: %s, message: %s", projectUuid, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new DependencyTrackClientHttpException(response.statusCode(), response.bodyAsString()));
                }
                // without any metrics calculated yet, the body is empty
                var body = response.body();
                if (body == null || body.length() == 0) {
                    return Uni.createFrom().item(Optional.<ProjectMetrics>empty());
                }
                return Uni.createFrom().item(ProjectMetrics.fromJson(response.bodyAsJsonObject()));
            });
    }

    /**
     * The current metrics of the [projects] summed up, which only exist if Dependency-Track calculated them for every project after
     * it processed its last BOM, otherwise they still reflect an earlier state (before its vulnerabilities were analysed).
     * Fails if the metrics of any project can't be fetched.
     *
     * @return empty if there are no projects, or the metrics of any of them are not current
     */
    public Uni<Optional<ProjectMetrics>> getCurrentMetrics(List<DependencyTrackProject> projects) {
        if (projects.isEmpty()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Multi.createFrom().iterable(projects)
            .onItem().transformToUni(project -> getProjectMetrics(project.uuid())
                .map(metrics -> metrics.filter(current -> project.lastBomImport().map(imported -> !current.lastOccurrence().isBefore(imported)).orElse(true))))
            .merge(METRICS_CONCURRENCY)
            .collect().asList()
            .map(results -> results.stream().allMatch(Optional::isPresent)
                ? results.stream().flatMap(Optional::stream).reduce(ProjectMetrics::plus)
                : Optional.<ProjectMetrics>empty());
    }

    /**
     * @return the web URL of the project
     */
    public String projectUrl(String projectUuid) {
        return "%s/projects/%s".formatted(dtrackBaseUrl, projectUuid);
    }

    /**
     * Lists the direct children of a project, in any state.
     * Fails if the children can't be listed completely.
     */
    public Uni<List<DependencyTrackProject>> listChildren(String projectUuid) {
        return listPages("/project/" + projectUuid + "/children", null, false, 1, new ArrayList<>()).map(DependencyTrackClient::uniqueProjects);
    }

    private static List<DependencyTrackProject> uniqueProjects(List<JsonObject> projects) {
        // projects changed while paging might show up twice
        var unique = new LinkedHashMap<String, DependencyTrackProject>();
        projects.stream()
            .map(DependencyTrackProject::fromJson)
            .filter(project -> project.uuid() != null)
            .forEach(project -> unique.putIfAbsent(project.uuid(), project));
        return List.copyOf(unique.values());
    }

    private Uni<List<JsonObject>> listProjectsBySearchText(String searchText, boolean excludeInactive, int pageNumber, List<JsonObject> collected) {
        return listPages("/project", searchText, excludeInactive, pageNumber, collected);
    }

    private Uni<List<JsonObject>> listPages(String path, String searchText, boolean excludeInactive, int pageNumber, List<JsonObject> collected) {
        var scope = searchText != null ? "matching " + searchText : path.equals("/project") ? "in total" : "of " + path;
        var request = client.getAbs(dtrackApiUrl + path);
        if (searchText != null) {
            request.addQueryParam("searchText", searchText);
        }
        return request
            .addQueryParam("excludeInactive", String.valueOf(excludeInactive))
            .addQueryParam("pageSize", String.valueOf(PAGE_SIZE))
            .addQueryParam("pageNumber", String.valueOf(pageNumber))
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to list projects %s", scope))
            .onItem().invoke(deprecationCheck("GET /project"))
            .chain(response -> {
                if (response.statusCode() != 200) {
                    Log.errorf("Failed to list projects %s, status: %s, message: %s", scope, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().<List<JsonObject>>failure(new DependencyTrackClientHttpException(response.statusCode(), response.bodyAsString()));
                }
                var page = response.bodyAsJsonArray();
                for (int i = 0; i < page.size(); i++) {
                    collected.add(page.getJsonObject(i));
                }

                var totalCount = Optional.ofNullable(response.getHeader("X-Total-Count")).map(String::trim).flatMap(DependencyTrackClient::parseInt);
                var lastPage = page.size() < PAGE_SIZE || totalCount.map(total -> pageNumber * PAGE_SIZE >= total).orElse(false);
                if (lastPage) {
                    // fewer items than announced means the listing is incomplete, which must not be mistaken for a complete one
                    if (totalCount.isPresent() && collected.size() < totalCount.get()) {
                        Log.errorf("Listing projects %s is incomplete, got %d of %d projects", scope, collected.size(), totalCount.get());
                        return Uni.createFrom().<List<JsonObject>>failure(new IllegalStateException(
                            "Listing projects %s is incomplete, got %d of %d projects".formatted(scope, collected.size(), totalCount.get())));
                    }
                    return Uni.createFrom().item((List<JsonObject>) collected);
                }
                return listPages(path, searchText, excludeInactive, pageNumber + 1, collected);
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
        return RepositoryProjectMatcher.matchesRepoName(project.getString("name"), repoName);
    }

    /**
     * Selects the projects belonging to the repository, see {@link RepositoryProjectMatcher#select}
     */
    List<JsonObject> selectProjectsOfRepository(List<JsonObject> candidates, String repoName, String vcsUrl, String websiteUrl) {
        var byUuid = new LinkedHashMap<String, JsonObject>();
        candidates.forEach(candidate -> byUuid.putIfAbsent(candidate.getString("uuid"), candidate));
        var projects = byUuid.values().stream().map(DependencyTrackProject::fromJson).toList();
        return RepositoryProjectMatcher.select(projects, repoName, vcsUrl, websiteUrl).stream()
            .map(project -> byUuid.get(project.uuid()))
            .toList();
    }

    /**
     * URLs of the VCS and website external references of the project, empty if there are none
     */
    static Stream<String> repositoryReferenceUrls(JsonObject project) {
        var extRefs = project.getJsonArray("externalReferences");
        if (extRefs == null) {
            return Stream.empty();
        }
        return extRefs.stream()
            .filter(o -> o instanceof JsonObject)
            .map(o -> (JsonObject) o)
            .filter(ref -> {
                var type = ref.getString("type");
                return type == null
                    || type.equalsIgnoreCase(ExternalReference.Type.VCS.getTypeName())
                    || type.equalsIgnoreCase(ExternalReference.Type.WEBSITE.getTypeName());
            })
            .map(ref -> ref.getString("url"))
            .filter(url -> url != null && !url.isBlank());
    }

    private static boolean isChildProject(JsonObject project, String repoName) {
        return project.getJsonObject("parent") != null || !repoName.equals(project.getString("name"));
    }

    /**
     * @return true if the project was deactivated successfully
     */
    Uni<Boolean> deactivateProject(String projectUUID) {
        return changeProjectState(projectUUID, false);
    }

    /**
     * Changes the state of the project only, 304 (not modified) means it's in that state already
     *
     * @return true if the project is in the desired state
     */
    private Uni<Boolean> changeProjectState(String projectUUID, boolean active) {
        var action = active ? "enable" : "disable";
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(JsonObject.of("active", active))
            .onFailure().retry().atMost(3)
            .onItem().invoke(deprecationCheck("PATCH /project/{uuid}"))
            .map(response -> {
                if (response.statusCode() == 200 || response.statusCode() == 304) {
                    Log.infof("%s project %s", active ? "Enabled" : "Disabled", projectUUID);
                    return true;
                }
                Log.warnf("Failed to %s project %s with %s: %s", action, projectUUID, response.statusCode(), response.bodyAsString());
                return false;
            })
            .onFailure().invoke(e -> Log.warnf(e, "Failed to %s project %s", action, projectUUID))
            .onFailure().recoverWithItem(false);
    }

    Uni<Void> updateProjectMetaData(String projectUUID, RepositoryDetails repoDetails, Project parentProject, Optional<String> commitSha, Optional<String> version) {
        var projectDetails = createProjectBaseData(repoDetails, commitSha, version);
        if (parentProject instanceof Project.Available parent) {
            projectDetails.put("parent", JsonObject.of("uuid", parent.projectId()));
        }
        return patchProjectMetaData(projectUUID, projectDetails).replaceWithVoid();
    }

    /**
     * Replaces the external references and tags of the project, and nothing else: in contrast to {@link #updateProjectMetaData}
     * the description, state and parent are left as they are, so it's safe to be used for projects not being uploaded currently, e.g. archived ones.
     * Like with the upload, all references and tags of the project are replaced by the given ones.
     *
     * @return true if the project was updated successfully
     */
    public Uni<Boolean> updateProjectReferencesAndTags(String projectUUID, List<DependencyTrackProject.Reference> references, List<String> tags) {
        return patchProjectMetaData(projectUUID, JsonObject.of(
            "tags", tagsToJson(tags),
            "externalReferences", new JsonArray(references.stream().map(DependencyTrackProject.Reference::toJson).toList())
        ));
    }

    /**
     * The references to a repository, like they're put to every project created for it: VCS, website and release notes
     */
    public static List<DependencyTrackProject.Reference> repositoryReferences(String vcsUrl, String websiteUrl) {
        return List.of(
            new DependencyTrackProject.Reference(ExternalReference.Type.VCS.getTypeName(), vcsUrl),
            new DependencyTrackProject.Reference(ExternalReference.Type.WEBSITE.getTypeName(), websiteUrl),
            new DependencyTrackProject.Reference(ExternalReference.Type.RELEASE_NOTES.getTypeName(), websiteUrl + "/releases")
        );
    }

    private Uni<Boolean> patchProjectMetaData(String projectUUID, JsonObject projectDetails) {
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(projectDetails)
            .onItem().invoke(deprecationCheck("PATCH /project/{uuid}"))
            .map(Unchecked.function(response -> {
                if (response.statusCode() == 200 || response.statusCode() == 304) {
                    Log.infof("Updated projects %s metadata", projectUUID);
                    return true;
                }
                // e.g. 409 on concurrent modification by Dependency-Track itself, worth another attempt
                throw new DependencyTrackClientHttpException(response.statusCode(), response.bodyAsString());
            }))
            .onFailure().retry().withBackOff(pollInterval).atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to update projects %s metadata: %s", projectUUID, e.getMessage()))
            .onFailure().recoverWithItem(false);
    }

    private static JsonArray tagsToJson(List<String> tags) {
        return new JsonArray(tags.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(tag -> JsonObject.of("name", tag))
            .toList());
    }

    JsonObject createProjectBaseData(RepositoryDetails repoDetails, Optional<String> commitSha, Optional<String> version) {
        var tagsArray = tagsToJson(repoDetails.topics());
        // prefix description with #commitSha# to allow VCS matching
        var shaDesc = createCommitShaDescriptionPrefix(commitSha);
        var shaAndVersionDesc = shaDesc + version.map(v -> v + " | ").orElse("");
        var description = Optional.ofNullable(repoDetails.description()).map(String::trim).map(shaAndVersionDesc::concat).orElse(shaAndVersionDesc);
        // dtrack allows a max of 255 chars for description
        var descValue = description.length() < 255 ? description : (description.substring(0, 251) + "...");
        var extRefs = new JsonArray(repositoryReferences(repoDetails.vcsUrl(), repoDetails.websiteUrl()).stream()
            .map(DependencyTrackProject.Reference::toJson)
            .toList());
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

    private static final Pattern COMMIT_SHA_DESCRIPTION = Pattern.compile("^#(\\w{1,7})#.*", Pattern.DOTALL);

    /**
     * Extracts the commit hash from a given project description prefixed by {@link #createCommitShaDescriptionPrefix(Optional)}
     */
    Optional<String> parseCommitShaFromDescription(String description) {
        if (description == null) {
            return Optional.empty();
        }
        var matcher = COMMIT_SHA_DESCRIPTION.matcher(description);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    boolean isDifferentVersionOfSameProject(JsonObject project, String projectName, String projectVersion) {
        return
            Objects.equals(project.getString("name"), projectName)
                && !Objects.equals(project.getString("version"), projectVersion);
    }

}
