package com.mediamarktsaturn.ghbot.events;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;

import java.io.IOException;
import java.net.URL;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.ArgumentMatcher;
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
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(pushHandler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", null))));
    }

    @Test
    public void testConfigProjectName() throws IOException {
        // Given
        var config = new TechnolinatorConfig(
            null,
            new TechnolinatorConfig.ProjectConfig("overriddenName"),
            null
        );

        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/project_name_override.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(pushHandler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config))));
    }

    @Test
    public void testConfigDisabled() throws IOException {
        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/disabled.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(pushHandler));
    }

    @Test
    public void testConfigAllOptions() throws IOException {
        // Given
        var config = new TechnolinatorConfig(
            true,
            new TechnolinatorConfig.ProjectConfig("awesomeProject"),
            new TechnolinatorConfig.AnalysisConfig("projectLocation", true)
        );

        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/full_blown.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(pushHandler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config))));
    }

    static ArgumentMatcher<PushEvent> matches(String repoUrl, String pushRef, String defaultBranch, TechnolinatorConfig config) {
        return got ->
            got.repoUrl().equals(url(repoUrl))
                && got.pushRef().equals(pushRef)
                && got.defaultBranch().equals(defaultBranch)
                && got.config().equals(Optional.ofNullable(config));
    }

    static URL url(String url) {
        try {
            return new URL(url);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
