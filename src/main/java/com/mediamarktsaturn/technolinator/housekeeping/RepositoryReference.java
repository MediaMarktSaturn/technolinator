package com.mediamarktsaturn.technolinator.housekeeping;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Identifies a repository on a GitHub host independent of the way it's referenced (web URL, git URL, with or without {@code .git}).
 * All parts are lower cased, as GitHub treats them case-insensitively.
 */
public record RepositoryReference(String host, String owner, String name) {

    /**
     * Extracts the repository from the URL of a repository, like {@code https://github.com/owner/repo},
     * {@code git://github.com/owner/repo.git} or derived ones like {@code https://github.com/owner/repo/releases}.
     *
     * @return empty if the URL doesn't denote a repository
     */
    public static Optional<RepositoryReference> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (uri.getHost() == null || uri.getPath() == null) {
            return Optional.empty();
        }
        var parts = Arrays.stream(uri.getPath().split("/")).filter(segment -> !segment.isEmpty()).toList();
        if (parts.size() < 2) {
            return Optional.empty();
        }
        var name = parts.get(1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryReference(
            uri.getHost().toLowerCase(Locale.ROOT),
            parts.get(0).toLowerCase(Locale.ROOT),
            name.toLowerCase(Locale.ROOT)
        ));
    }

    String ownerKey() {
        return host + "/" + owner;
    }

    @Override
    public String toString() {
        return host + "/" + owner + "/" + name;
    }
}
