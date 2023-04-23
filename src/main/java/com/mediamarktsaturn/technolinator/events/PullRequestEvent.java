package com.mediamarktsaturn.technolinator.events;

import java.net.URL;
import java.util.Optional;

import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;

/**
 * Data type used to transport pull-request event notifications along the process
 */
public record PullRequestEvent(
    GHEventPayload.PullRequest payload,
    Optional<TechnolinatorConfig> config
) implements Event<GHEventPayload.PullRequest> {

    @Override
    public String branch() {
        return payload.getPullRequest().getHead().getRef();
    }

    @Override
    public String ref() {
        return "refs/heads/" + branch();
    }

    public URL repoUrl() {
        return payload.getRepository().getHtmlUrl();
    }

    public String defaultBranch() {
        return payload.getRepository().getDefaultBranch();
    }

    @Override
    public GHRepository repository() {
        return payload.getRepository();
    }
}
