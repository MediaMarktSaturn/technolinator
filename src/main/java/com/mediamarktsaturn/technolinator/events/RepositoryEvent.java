package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.net.URL;
import java.util.Optional;

/**
 * Data type used to transport push event notifications along the process
 */
public record RepositoryEvent(
    GHEventPayload.Repository payload,
    Optional<TechnolinatorConfig> config
) implements Event<GHEventPayload.Repository> {

    @Override
    public String branch() {
        return defaultBranch();
    }

    @Override
    public URL repoUrl() {
        return repository().getUrl();
    }

    @Override
    public String ref() {
        return "";
    }

    @Override
    public String defaultBranch() {
        return repository().getDefaultBranch();
    }

    @Override
    public GHRepository repository() {
        return payload.getRepository();
    }
}
