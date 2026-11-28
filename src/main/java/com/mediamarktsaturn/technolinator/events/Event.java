package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.kohsuke.github.GHRepository;

import java.net.URL;
import java.util.Optional;

public interface Event {

    String branch();

    String ref();

    URL repoUrl();

    String defaultBranch();

    GHRepository repository();

    String version();

    Optional<TechnolinatorConfig> config();

    default boolean isDefaultBranch() {
        return branch().equals(defaultBranch());
    }

    /**
     * Whether the code to analyse originates from another repository (a fork), so it isn't trusted to run anything on its own.
     */
    default boolean fromFork() {
        return false;
    }

    default String getRepoName() {
        var path = repoUrl().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
