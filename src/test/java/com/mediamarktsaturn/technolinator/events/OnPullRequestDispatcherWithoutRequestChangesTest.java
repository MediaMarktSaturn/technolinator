package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.CustomTestProfiles;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.handler.AnalysisOutcome;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import com.mediamarktsaturn.technolinator.report.PullRequestReviewer;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHEvent;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Optional;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Pull-requests are not reviewed if requesting changes is switched off, while their commit status still tells about the vulnerabilities.
 */
@QuarkusTest
@GitHubAppTest
@TestProfile(CustomTestProfiles.RequestChangesDisabled.class)
class OnPullRequestDispatcherWithoutRequestChangesTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @InjectMock
    PullRequestReviewer reviewer;

    @Test
    void doesNotReview() throws IOException {
        Mockito.when(handler.onPullRequest(any(), any())).thenReturn(Uni.createFrom().item(
            new AnalysisOutcome(Result.success(Project.none()), Optional.of(Result.success(new VulnerabilitySummary(1, 0, 0, 0, 0))))));

        GitHubAppTesting.when()
            .payloadFromClasspath("/events/pull_request_opened.json")
            .event(GHEvent.PULL_REQUEST)
            .then()
            .github(mocks -> {
                await().untilAsserted(() -> Mockito.verify(mocks.repository("heubeck/app-test")).createCommitStatus(
                    any(), eq(GHCommitState.FAILURE), any(), any(), any()));
                Mockito.verifyNoInteractions(reviewer);
            });
    }
}
