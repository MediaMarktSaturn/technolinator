package com.mediamarktsaturn.ghbot.events;

import java.io.IOException;
import java.net.URL;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.Mockito;

import com.mediamarktsaturn.ghbot.handler.PushHandler;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTest
@GitHubAppTest
public class OnPushDispatcherTest {

    @InjectMock
    PushHandler pushHandler;

    @BeforeEach
    public void setup() {
        Mockito.reset(pushHandler);
    }

    @Test
    public void testOnPushToDefaultBranch() throws IOException {
        // Given
        var pushEvent = new PushEvent(new URL("https://github.com/heubeck/app-test"), "refs/heads/main", "main");

        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        Mockito.verify(pushHandler).onPush(pushEvent);
    }
}
