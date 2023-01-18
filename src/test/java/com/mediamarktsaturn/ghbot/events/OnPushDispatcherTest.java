package com.mediamarktsaturn.ghbot.events;

import java.io.IOException;
import java.net.URL;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.Mockito;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
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
        var pushEvent = new PushEvent(new URL("https://github.com/heubeck/app-test"), "refs/heads/main", "main", Optional.empty());

        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        Mockito.verify(pushHandler).onPush(pushEvent);
    }

    @Test
    public void testConfigProjectName() throws IOException {
        // Given
        var config = new TechnolinatorConfig(
            null,
            new TechnolinatorConfig.ProjectConfig("overriddenName"),
            null
        );
        var pushEvent = new PushEvent(new URL("https://github.com/heubeck/app-test"), "refs/heads/main", "main", Optional.of(config));

        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/project_name_override.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        Mockito.verify(pushHandler).onPush(pushEvent);
    }

    @Test
    public void testConfigDisabled() throws IOException {
        // Given
        var config = new TechnolinatorConfig(
            false,
            null,
            null
        );
        var pushEvent = new PushEvent(new URL("https://github.com/heubeck/app-test"), "refs/heads/main", "main", Optional.of(config));

        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/disabled.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        Mockito.verifyNoInteractions(pushHandler);
    }

    @Test
    public void testConfigAllOptions() throws IOException {
        // Given
        var config = new TechnolinatorConfig(
            true,
            new TechnolinatorConfig.ProjectConfig("awesomeProject"),
            new TechnolinatorConfig.AnalysisConfig("projectLocation", true)
        );
        var pushEvent = new PushEvent(new URL("https://github.com/heubeck/app-test"), "refs/heads/main", "main", Optional.of(config));

        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/full_blown.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        Mockito.verify(pushHandler).onPush(pushEvent);
    }
}
