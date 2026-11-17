package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.net.URL;
import java.util.Optional;

/**
 * Data type used to transport push event notifications along the process.
 * Not bound to a webhook payload, so that an analysis can also be triggered on demand, see {@link AnalysisTrigger}.
 *
 * @param repository The repository pushed to
 * @param ref        The full git ref pushed to, like {@code refs/heads/main}
 * @param version    The head commit of the push
 * @param config     The repository specific configuration
 */
public record PushEvent(
    GHRepository repository,
    String ref,
    String version,
    Optional<TechnolinatorConfig> config
) implements Event {

    static final String BRANCH_REF_PREFIX = "refs/heads/";

    public PushEvent(GHEventPayload.Push payload, Optional<TechnolinatorConfig> config) {
        this(payload.getRepository(), payload.getRef(), payload.getHead(), config);
    }

    @Override
    public String branch() {
        return ref.startsWith(BRANCH_REF_PREFIX) ? ref.substring(BRANCH_REF_PREFIX.length()) : ref;
    }

    @Override
    public URL repoUrl() {
        return repository.getUrl();
    }

    @Override
    public String defaultBranch() {
        return repository.getDefaultBranch();
    }
}
