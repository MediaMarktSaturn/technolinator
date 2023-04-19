package com.mediamarktsaturn.technolinator.sbom;

/**
 * Representation of a Dependency-Track project
 */
public sealed interface Project {

    record Available(String url, String projectId) implements Project {
    }

    record None() implements Project {
    }

    static Project available(String url, String projectId) {
        return new Available(url, projectId);
    }

    static Project none() {
        return new None();
    }
}
