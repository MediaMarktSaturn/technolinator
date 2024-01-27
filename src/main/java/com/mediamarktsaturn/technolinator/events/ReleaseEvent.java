package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.net.URL;
import java.util.Optional;

/**
 * Data type used to transport push event notifications along the process
 */
public record ReleaseEvent(
    GHEventPayload.Release payload,
    Optional<TechnolinatorConfig> config
) implements Event<GHEventPayload.Release> {

    @Override
    public String branch() {
        return payload.getRelease().getTargetCommitish();
    }

    @Override
    public URL repoUrl() {
        return payload.getRepository().getUrl();
    }

    @Override
    public String ref() {
        return payload.getRelease().getTagName();
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
