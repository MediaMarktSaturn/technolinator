package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Determines how the external references and tags of a Dependency-Track project of a GitHub repository need to look like, according to what
 * Technolinator puts to the projects when uploading a BOM (see {@link DependencyTrackClient#repositoryReferences} and
 * {@code RepositoryService#getProjectTopics}).
 * <p>
 * <b>References:</b> VCS, website and release notes of the repository. References of other types are kept.
 * <p>
 * <b>Tags:</b> {@code owner=<owner>} and the topics of the repository, as the upload does.
 * Tags the upload adds on top of those are derived from the analysis (quality score of the BOM) or the content of the repository
 * (backstage annotations), neither of which is available here. They're recognizable by having the form {@code key=value}, which can't
 * be a topic of GitHub, so they're kept as they are.
 */
final class ProjectMetadata {

    private static final String OWNER_TAG_PREFIX = "owner=";

    private ProjectMetadata() {
    }

    /**
     * What a project is to be updated with
     */
    record Update(List<DependencyTrackProject.Reference> references, List<String> tags) {
    }

    /**
     * @return true if the project lacks one of the references to the repository, or the owner tag, which is what's looked at without knowing the topics
     */
    static boolean lacksRepositoryMetadata(DependencyTrackProject project, GitHubRepo repository) {
        var references = referenceKeys(project.externalReferences());
        var hasAllReferences = referenceKeys(DependencyTrackClient.repositoryReferences(repository.gitUrl(), repository.htmlUrl())).stream()
            .allMatch(references::contains);
        return !hasAllReferences || !tagKeys(project.tags()).contains((OWNER_TAG_PREFIX + repository.ownerName()).toLowerCase(Locale.ROOT));
    }

    /**
     * @param topics the topics of the repository
     * @return the metadata the project is to be updated with, empty if it has it already
     */
    static Optional<Update> requiredUpdate(DependencyTrackProject project, GitHubRepo repository, List<String> topics) {
        var references = desiredReferences(project, repository);
        var tags = desiredTags(project, repository, topics);

        var referencesUpToDate = referenceKeys(references).equals(referenceKeys(project.externalReferences()));
        var tagsUpToDate = tagKeys(tags).equals(tagKeys(project.tags()));
        return referencesUpToDate && tagsUpToDate ? Optional.empty() : Optional.of(new Update(references, tags));
    }

    private static List<DependencyTrackProject.Reference> desiredReferences(DependencyTrackProject project, GitHubRepo repository) {
        var references = new ArrayList<>(DependencyTrackClient.repositoryReferences(repository.gitUrl(), repository.htmlUrl()));
        var ownTypes = references.stream().map(ref -> normalizeType(ref.type())).collect(Collectors.toSet());
        project.externalReferences().stream()
            .filter(ref -> ref.type() != null && !ownTypes.contains(normalizeType(ref.type())))
            .forEach(references::add);
        return references;
    }

    private static List<String> desiredTags(DependencyTrackProject project, GitHubRepo repository, List<String> topics) {
        // keyed by lower case, Dependency-Track doesn't distinguish tags by case
        var tags = new LinkedHashMap<String, String>();
        add(tags, OWNER_TAG_PREFIX + repository.ownerName());
        topics.forEach(topic -> add(tags, topic));
        project.tags().stream()
            .filter(tag -> tag.contains("=") && !tag.toLowerCase(Locale.ROOT).startsWith(OWNER_TAG_PREFIX))
            .forEach(tag -> add(tags, tag));
        return List.copyOf(tags.values());
    }

    private static void add(LinkedHashMap<String, String> tags, String tag) {
        if (tag != null && !tag.isBlank()) {
            tags.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
        }
    }

    private static Set<String> tagKeys(List<String> tags) {
        return tags.stream().map(tag -> tag.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static Set<String> referenceKeys(List<DependencyTrackProject.Reference> references) {
        return references.stream().map(ref -> normalizeType(ref.type()) + " " + ref.url()).collect(Collectors.toSet());
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
