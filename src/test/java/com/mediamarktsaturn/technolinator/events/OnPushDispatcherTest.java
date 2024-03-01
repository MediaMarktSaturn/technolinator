package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.smallrye.mutiny.Uni;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.url;
import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@QuarkusTest
@GitHubAppTest
class OnPushDispatcherTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
    }

    @Test
    void testOnPushToDefaultBranch() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler)
            .onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", null)), any()));

        RestAssured.get("/q/metrics")
            .then()
            .statusCode(200)
            .body(CoreMatchers.containsString("""
                repo_analysis_current_count{kind="push",repo="app-test"} 0.0
                """));
    }

    @Test
    void testConfigProjectName() throws IOException {
        // Given
        var config = ConfigBuilder.create().project(new TechnolinatorConfig.ProjectConfig("overriddenName")).build();
        Mockito.when(handler.onPush(any(), any())).thenReturn(Uni.createFrom().item(Result.success(Project.none())));

        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/project_name_override.yml"))
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config)), any()));
    }

    @Test
    void testConfigDisabled() throws IOException {
        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/disabled.yml"))
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    @Test
    void testConfigAllOptions() throws IOException {
        // Given
        var config = ConfigBuilder.create()
            .enable(true)
            .project(new TechnolinatorConfig.ProjectConfig("awesomeProject"))
            .analysis(new TechnolinatorConfig.AnalysisConfig("projectLocation", true, true, true, true, null))
            .build();

        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/full_blown.yml"))
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config)), any()));
    }

    @Test
    void testRepoEnabledConfig_noRestriction() {
        // Given
        var cur = new OnPushDispatcher();
        cur.enabledRepos = List.of();

        // When && Then
        assertThat(cur.isEnabledByConfig("technolinator")).isTrue();
    }

    @Test
    void testRepoEnabledConfig_restriction() {
        // Given
        var cur = new OnPushDispatcher();
        cur.enabledRepos = List.of(" technolinator ", "", " analyzeMe");

        // When && Then
        assertThat(cur.isEnabledByConfig("technolinator")).isTrue();
        assertThat(cur.isEnabledByConfig("fluggegecheimen")).isFalse();
    }

    static ArgumentMatcher<PushEvent> matches(String repoUrl, String pushRef, String defaultBranch, TechnolinatorConfig config) {
        return got ->
            got.repoUrl().sameFile(url(repoUrl))
                && got.ref().equals(pushRef)
                && got.defaultBranch().equals(defaultBranch)
                && got.config().equals(Optional.ofNullable(config));
    }
}
