package com.mediamarktsaturn.ghbot.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import com.mediamarktsaturn.ghbot.ConfigBuilder;
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
    void testOnPushToDefaultBranch() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(pushHandler)
            .onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", null)), any()));
    }

    @Test
    void testConfigProjectName() throws IOException {
        // Given
        var config = ConfigBuilder.create().project(new TechnolinatorConfig.ProjectConfig("overriddenName")).build();

        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/project_name_override.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(pushHandler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config)), any()));
    }

    @Test
    void testConfigDisabled() throws IOException {
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
    void testConfigAllOptions() throws IOException {
        // Given
        var config = ConfigBuilder.create()
            .enable(true)
            .project(new TechnolinatorConfig.ProjectConfig("awesomeProject"))
            .analysis(new TechnolinatorConfig.AnalysisConfig("projectLocation", true, null))
            .build();

        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile("technolinator.yml").fromClasspath("/configs/full_blown.yml");
            })
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(pushHandler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config)), any()));
    }

    @Test
    void testRepoEnabledConfig_noRestriction() throws MalformedURLException {
        // Given
        var cur = new OnPushDispatcher();
        cur.enabledRepos = List.of();
        var repo = cur.getRepoName(new URL("https://github.com/MediaMarktSaturn/technolinator"));

        // When && Then
        assertThat(cur.isEnabledByConfig(repo)).isTrue();
    }

    @Test
    void testRepoEnabledConfig_restriction() throws MalformedURLException {
        // Given
        var cur = new OnPushDispatcher();
        cur.enabledRepos = List.of(" technolinator ", "", " analyzeMe");
        var enabledRepo = cur.getRepoName(new URL("https://github.com/MediaMarktSaturn/technolinator"));
        var disabledRepo = cur.getRepoName(new URL("https://github.com/MediaMarktSaturn/fluggegecheimen"));

        // When && Then
        assertThat(cur.isEnabledByConfig(enabledRepo)).isTrue();
        assertThat(cur.isEnabledByConfig(disabledRepo)).isFalse();
    }

    static ArgumentMatcher<PushEvent> matches(String repoUrl, String pushRef, String defaultBranch, TechnolinatorConfig config) {
        return got ->
            got.repoUrl().sameFile(url(repoUrl))
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
