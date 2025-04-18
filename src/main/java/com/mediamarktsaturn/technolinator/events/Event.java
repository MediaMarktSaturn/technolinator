package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.net.URL;
import java.util.Optional;

public interface Event<P extends GHEventPayload> {

    P payload();

    String branch();

    String ref();

    URL repoUrl();

    String defaultBranch();

    GHRepository repository();

    String version();

    Optional<TechnolinatorConfig> config();

    default boolean targetsDefaultBranch() {
        return branch().equals(defaultBranch());
    }

    default boolean isDefaultBranch() {
        return branch().equals(defaultBranch());
    }

    default String getRepoName() {
        var path = repoUrl().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
