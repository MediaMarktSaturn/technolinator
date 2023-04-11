package com.mediamarktsaturn.technolinator.events;

import java.net.URL;
import java.util.Optional;

import org.kohsuke.github.GHEventPayload;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;

/**
 * Data type used to transport push event notifications along the process
 */
public record PushEvent(
    GHEventPayload.Push pushPayload,
    Optional<TechnolinatorConfig> config
) {
    public String getBranch() {
        return pushRef().replaceFirst("refs/heads/", "");
    }

    public URL repoUrl() {
        return pushPayload.getRepository().getUrl();
    }

    public String pushRef() {
        return pushPayload.getRef();
    }

    public String defaultBranch() {
        return pushPayload.getRepository().getDefaultBranch();
    }
}
