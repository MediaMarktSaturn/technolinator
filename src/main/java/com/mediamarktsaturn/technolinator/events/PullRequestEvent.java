package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.net.URL;
import java.util.Optional;

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

    @Override
    public URL repoUrl() {
        return repository().getHtmlUrl();
    }

    @Override
    public String defaultBranch() {
        return repository().getDefaultBranch();
    }

    @Override
    public boolean targetsDefaultBranch() {
        return payload.getPullRequest().getBase().getRef().equals(defaultBranch());
    }

    @Override
    public GHRepository repository() {
        return payload.getRepository();
    }
}
