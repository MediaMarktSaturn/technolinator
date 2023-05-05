package com.mediamarktsaturn.technolinator.events;

import java.net.URL;
import java.util.Optional;

import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;

public interface Event<P extends GHEventPayload> {

    P payload();

    String branch();

    String ref();

    URL repoUrl();

    String defaultBranch();

    GHRepository repository();

    Optional<TechnolinatorConfig> config();
}
