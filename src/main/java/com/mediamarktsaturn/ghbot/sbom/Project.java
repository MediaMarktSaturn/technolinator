package com.mediamarktsaturn.ghbot.sbom;

public sealed interface Project {

    record Available(String url) implements Project {
    }

    record None() implements Project {
    }

    static Project available(String url) {
        return new Available(url);
    }

    static Project none() {
        return new None();
    }
}
