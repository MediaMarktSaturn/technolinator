package com.mediamarktsaturn.technolinator.sbom;

import java.util.Optional;

/**
 * Representation of a Dependency-Track project
 */
public sealed interface Project {

    record List(String searchUrl) implements Project {
    }

    record Available(String url, String projectId, Optional<String> commitSha) implements Project {
    }

    record None() implements Project {
    }

    static Project list(String searchUrl) {
        return new List(searchUrl);
    }

    static Project available(String url, String projectId, Optional<String> commitSha) {
        return new Available(url, projectId, commitSha);
    }

    static Project none() {
        return new None();
    }
}
