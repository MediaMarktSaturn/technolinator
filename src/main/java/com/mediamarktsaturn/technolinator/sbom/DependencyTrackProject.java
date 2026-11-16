package com.mediamarktsaturn.technolinator.sbom;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The parts of a Dependency-Track project housekeeping tasks work with
 *
 * @param uuid           Id of the project
 * @param name           Name of the project, for projects created by Technolinator the name of the repository or of a module of it
 * @param version        Version of the project, might be empty
 * @param active         Inactive projects are considered archived
 * @param parentUuid     Id of the parent, if the project is a child (e.g. module of a multi-module repository)
 * @param repositoryUrls URLs of the repository references (VCS and website) of the project, empty if the project doesn't have any
 * @param lastBomImport  Time of the last BOM upload, if any
 */
public record DependencyTrackProject(
    String uuid,
    String name,
    String version,
    boolean active,
    Optional<String> parentUuid,
    List<String> repositoryUrls,
    Optional<Instant> lastBomImport
) {
    static DependencyTrackProject fromJson(JsonObject project) {
        return new DependencyTrackProject(
            project.getString("uuid"),
            project.getString("name"),
            project.getString("version"),
            project.getBoolean("active", true),
            Optional.ofNullable(project.getJsonObject("parent")).map(parent -> parent.getString("uuid")),
            DependencyTrackClient.repositoryReferenceUrls(project).distinct().toList(),
            Optional.ofNullable(project.getValue("lastBomImport"))
                .filter(Number.class::isInstance)
                .map(value -> Instant.ofEpochMilli(((Number) value).longValue()))
        );
    }

    /**
     * Human readable identification for log messages
     */
    public String displayName() {
        return "%s in version %s (%s)".formatted(name, version, uuid);
    }
}
