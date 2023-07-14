package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.net.URL;
import java.util.Optional;

/**
 * Data type used to transport push event notifications along the process
 */
public record PushEvent(
    GHEventPayload.Push payload,
    Optional<TechnolinatorConfig> config
) implements Event<GHEventPayload.Push> {

    @Override
    public String branch() {
        return ref().replaceFirst("refs/heads/", "");
    }

    @Override
    public URL repoUrl() {
        return payload.getRepository().getUrl();
    }

    @Override
    public String ref() {
        return payload.getRef();
    }

    @Override
    public String defaultBranch() {
        return payload.getRepository().getDefaultBranch();
    }

    @Override
    public GHRepository repository() {
        return payload.getRepository();
    }
}
