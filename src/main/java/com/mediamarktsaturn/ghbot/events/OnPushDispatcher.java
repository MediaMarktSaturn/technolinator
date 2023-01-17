package com.mediamarktsaturn.ghbot.events;

import static com.mediamarktsaturn.ghbot.handler.PushHandler.ON_PUSH;

import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.kohsuke.github.GHEventPayload;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
public class OnPushDispatcher {

    // no-arg constructor needed for GitHub event consuming classes by the framework, thus no constructor injection here
    @Inject
    EventBus eventBus;

    void onPush(@Push GHEventPayload.Push pushPayload, @ConfigFile("technolinator.yml") Optional<TechnolinatorConfig> config) {
        if (config.map(TechnolinatorConfig::enable).orElse(true)) {
            eventBus.send(ON_PUSH, new PushEvent(
                pushPayload.getRepository().getUrl(),
                pushPayload.getRef(),
                pushPayload.getRepository().getDefaultBranch(),
                config
            ));
        } else {
            Log.infof("Disabled for repo %s", pushPayload.getRepository().getUrl());
        }
    }
}
