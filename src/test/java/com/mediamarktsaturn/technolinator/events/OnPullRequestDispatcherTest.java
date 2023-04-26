package com.mediamarktsaturn.technolinator.events;

import static com.mediamarktsaturn.technolinator.TestUtil.url;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

import java.io.IOException;
import java.util.Optional;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kohsuke.github.GHEvent;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.PullRequestHandler;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.restassured.RestAssured;

@QuarkusTest
@GitHubAppTest
class OnPullRequestDispatcherTest {

    @InjectMock
    PullRequestHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/events/pull_request_opened.json",
        "/events/pull_request_reopened.json",
        "/events/pull_request_opened.json"
    })
    void testPullRequestActionable(String payloadClassPath) throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath(payloadClassPath)
            .event(GHEvent.PULL_REQUEST);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler)
            .onPullRequest(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/test/branch", "main", null)), any()));

        RestAssured.get("/q/metrics")
            .then()
            .statusCode(200)
            .body(CoreMatchers.containsString("""
                repo_analysis_current_count{kind="pull-request",repo="app-test"} 0.0
                """));
    }

    @Test
    void testPullRequestClosed() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/pull_request_closed.json")
            .event(GHEvent.PULL_REQUEST);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    static ArgumentMatcher<PullRequestEvent> matches(String repoUrl, String pushRef, String defaultBranch, TechnolinatorConfig config) {
        return got ->
            got.repoUrl().sameFile(url(repoUrl))
                && got.ref().equals(pushRef)
                && got.defaultBranch().equals(defaultBranch)
                && got.config().equals(Optional.ofNullable(config));
    }
}
