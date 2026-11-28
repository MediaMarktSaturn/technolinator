package com.mediamarktsaturn.technolinator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Quarkus 4 only provides a Jackson 3 mapper, whereas quarkus-github-app 3.0.0.CR1 (built against Quarkus 3)
 * still injects the unqualified Jackson 2 {@link ObjectMapper}.
 * TODO remove, once a quarkus-github-app release based on Quarkus 4 / Jackson 3 is available, see docs/Roadmap.md.
 */
public class JacksonCompatConfig {

    @Produces
    @Singleton
    @DefaultBean
    ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper();
    }
}
