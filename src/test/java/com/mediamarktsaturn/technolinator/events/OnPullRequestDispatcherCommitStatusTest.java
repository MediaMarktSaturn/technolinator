package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.CustomTestProfiles;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.handler.AnalysisOutcome;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
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
 * The commit status of the head commit of a pull-request tells about the vulnerabilities found.
 */
@QuarkusTest
@GitHubAppTest
@TestProfile(CustomTestProfiles.VulnerabilityThresholdHigh.class)
class OnPullRequestDispatcherCommitStatusTest {

    private static final String HEAD_SHA = "a6361dbb9f9238e858c6b5855ebe8d670a775614";
    private static final String CONTEXT = "Supply Chain Security";

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
    }

    private static AnalysisOutcome outcome(Optional<Result<VulnerabilitySummary>> vulnerabilities) {
        return new AnalysisOutcome(Result.success(Project.none()), vulnerabilities);
    }

    @FunctionalInterface
    private interface Verification {
        void accept(org.kohsuke.github.GHRepository repository) throws Exception;
    }

    private void openPullRequestAnd(Verification verification) throws IOException {
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/pull_request_opened.json")
            .event(GHEvent.PULL_REQUEST)
            .then()
            .github(mocks -> await().untilAsserted(() -> verification.accept(mocks.repository("heubeck/app-test"))));
    }

    @Test
    void tellsPendingAndThenTheVulnerabilitiesFailingTheThreshold() throws IOException {
        Mockito.when(handler.onPullRequest(any(), any()))
            .thenReturn(Uni.createFrom().item(outcome(Optional.of(Result.success(new VulnerabilitySummary(0, 2, 3, 4, 0))))));

        openPullRequestAnd(repository -> {
            var inOrder = Mockito.inOrder(repository);
            inOrder.verify(repository).createCommitStatus(eq(HEAD_SHA), eq(GHCommitState.PENDING), any(),
                eq("SBOM creation running, vulnerability status unknown"), eq(CONTEXT));
            inOrder.verify(repository).createCommitStatus(eq(HEAD_SHA), eq(GHCommitState.FAILURE), any(),
                eq("Vulnerabilities of severity high or above found: 0 critical, 2 high, 3 medium, 4 low"), eq(CONTEXT));
        });
    }

    @Test
    void tellsSuccessIfVulnerabilitiesAreBelowTheThreshold() throws IOException {
        Mockito.when(handler.onPullRequest(any(), any()))
            .thenReturn(Uni.createFrom().item(outcome(Optional.of(Result.success(new VulnerabilitySummary(0, 0, 3, 4, 0))))));

        openPullRequestAnd(repository -> Mockito.verify(repository).createCommitStatus(eq(HEAD_SHA), eq(GHCommitState.SUCCESS), any(),
            eq("No vulnerabilities of severity high or above (0 critical, 0 high, 3 medium, 4 low)"), eq(CONTEXT)));
    }

    @Test
    void tellsFailureWithUnknownVulnerabilitiesIfTheAnalysisFails() throws IOException {
        Mockito.when(handler.onPullRequest(any(), any()))
            .thenReturn(Uni.createFrom().item(outcome(Optional.of(Result.failure(new IllegalStateException("cdxgen failed"))))));

        openPullRequestAnd(repository -> Mockito.verify(repository).createCommitStatus(eq(HEAD_SHA), eq(GHCommitState.FAILURE), any(),
            eq("Analysis failed, vulnerability status unknown"), eq(CONTEXT)));
    }

    @Test
    void tellsFailureWithUnknownVulnerabilitiesIfTheAnalysisBreaks() throws IOException {
        Mockito.when(handler.onPullRequest(any(), any()))
            .thenReturn(Uni.createFrom().failure(new IllegalStateException("broken")));

        openPullRequestAnd(repository -> Mockito.verify(repository).createCommitStatus(eq(HEAD_SHA), eq(GHCommitState.FAILURE), any(),
            eq("Analysis failed, vulnerability status unknown"), eq(CONTEXT)));
    }

    @Test
    void tellsNothingFurtherIfVulnerabilitiesWereNotLookedAt() throws IOException {
        Mockito.when(handler.onPullRequest(any(), any()))
            .thenReturn(Uni.createFrom().item(outcome(Optional.empty())));

        openPullRequestAnd(repository -> {
            Mockito.verify(repository).createCommitStatus(eq(HEAD_SHA), eq(GHCommitState.PENDING), any(), any(), eq(CONTEXT));
            Mockito.verify(repository, Mockito.times(1)).createCommitStatus(any(), any(), any(), any(), any());
        });
    }
}
