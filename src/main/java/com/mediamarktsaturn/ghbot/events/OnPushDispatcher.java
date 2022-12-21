package com.mediamarktsaturn.ghbot.events;

import static com.mediamarktsaturn.ghbot.handler.PushHandler.ON_PUSH;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.event.Push;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
public class OnPushDispatcher {

    // no-arg constructor needed for github event consuming classes by the framework, thus no constructor injection here
    @Inject
    EventBus eventBus;

    void onPush(@Push GHEventPayload.Push pushPayload, GitHub gitHub) throws Exception {
        eventBus.send(ON_PUSH, new PushEvent(
            pushPayload.getRepository().getUrl(),
            pushPayload.getRef(),
            pushPayload.getRepository().getDefaultBranch()
        ));
    }

}
