package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Release;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.kohsuke.github.GHEventPayload;

import java.util.Optional;

import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;

@ApplicationScoped
public class OnReleaseDispatcher {

    @SuppressWarnings("unused")
        // called by the quarkus-github-app extension
    void onPush(@Release GHEventPayload.Release releasePayload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> config) {
        var release = releasePayload.getRelease();
        Log.infof("Repository %s released '%s' from tag '%s' targeting commit '%s'",
            releasePayload.getRepository().getUrl(),
            release.getName(),
            release.getTagName(),
            release.getTargetCommitish()
        );
    }
}
