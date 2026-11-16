package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.CustomTestProfiles;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.smallrye.mutiny.Uni;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.Mockito;

import java.io.IOException;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@QuarkusTest
@GitHubAppTest
@TestProfile(CustomTestProfiles.CommitStatusWriteDisabled.class)
class OnPushDispatcherWithDisabledCommitStatusWriteTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
        Mockito.when(handler.onPush(any(), any()))
            .thenReturn(Uni.createFrom().item(Result.success(new Project.None())));
    }

    @Test
    void testOnPushToDefaultBranch() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH)
            .then()
            .github(mocks -> Mockito
                .verify(mocks.repository("heubeck/app-test"), Mockito.never())
                .createCommitStatus(any(), any(), any(), any(), any()));

        // Then
        await().untilAsserted(() -> Mockito.verify(handler)
            .onPush(argThat(OnPushDispatcherTest.matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", null)), any()));

        // The analysis must complete although no commit status is written, i.e. the gauge is released again
        await().untilAsserted(() -> RestAssured.get("/q/metrics")
            .then()
            .statusCode(200)
            .body(CoreMatchers.containsString("""
                repo_analysis_current_count{kind="push",repo="app-test"} 0.0
                """)));
    }
}
