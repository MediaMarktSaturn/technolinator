package com.mediamarktsaturn.technolinator.sbom;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The parts of a Dependency-Track project housekeeping tasks work with
 *
 * @param uuid               Id of the project
 * @param name               Name of the project, for projects created by Technolinator the name of the repository or of a module of it
 * @param version            Version of the project, might be empty
 * @param active             Inactive projects are considered archived
 * @param parentUuid         Id of the parent, if the project is a child (e.g. module of a multi-module repository)
 * @param repositoryUrls     URLs of the repository references (VCS and website) of the project, empty if the project doesn't have any
 * @param lastBomImport      Time of the last BOM upload, if any
 * @param externalReferences All external references of the project, empty if the project doesn't have any
 * @param tags               Names of the tags of the project, empty if the project doesn't have any
 */
public record DependencyTrackProject(
    String uuid,
    String name,
    String version,
    boolean active,
    Optional<String> parentUuid,
    List<String> repositoryUrls,
    Optional<Instant> lastBomImport,
    List<Reference> externalReferences,
    List<String> tags
) {

    public DependencyTrackProject {
        externalReferences = List.copyOf(externalReferences);
        tags = List.copyOf(tags);
    }

    public DependencyTrackProject(
        String uuid,
        String name,
        String version,
        boolean active,
        Optional<String> parentUuid,
        List<String> repositoryUrls,
        Optional<Instant> lastBomImport
    ) {
        this(uuid, name, version, active, parentUuid, repositoryUrls, lastBomImport, List.of(), List.of());
    }

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
                .map(value -> Instant.ofEpochMilli(((Number) value).longValue())),
            parseReferences(project.getJsonArray("externalReferences")),
            parseTags(project.getJsonArray("tags"))
        );
    }

    private static List<Reference> parseReferences(JsonArray references) {
        if (references == null) {
            return List.of();
        }
        return references.stream()
            .filter(JsonObject.class::isInstance)
            .map(JsonObject.class::cast)
            .filter(ref -> ref.getString("url") != null && !ref.getString("url").isBlank())
            .map(ref -> new Reference(ref.getString("type"), ref.getString("url"), ref.getString("comment")))
            .toList();
    }

    private static List<String> parseTags(JsonArray tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
            .map(tag -> tag instanceof JsonObject json ? json.getString("name") : tag instanceof String string ? string : null)
            .filter(name -> name != null && !name.isBlank())
            .toList();
    }

    /**
     * Human readable identification for log messages
     */
    public String displayName() {
        return "%s in version %s (%s)".formatted(name, version, uuid);
    }

    /**
     * An external reference of a project
     *
     * @param type    Type as named by Dependency-Track, e.g. {@code vcs}, {@code website}
     * @param url     URL of the reference
     * @param comment Optional comment of the reference, might be null
     */
    public record Reference(String type, String url, String comment) {

        public Reference(String type, String url) {
            this(type, url, null);
        }

        public JsonObject toJson() {
            var json = JsonObject.of("type", type, "url", url);
            if (comment != null) {
                json.put("comment", comment);
            }
            return json;
        }
    }
}
