package com.mediamarktsaturn.technolinator.handler;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PullRequestEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilityReporting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Spliterators;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class PullRequestHandlingTest {

    @InjectSpy
    RepositoryService repoService;
    @InjectSpy
    CdxgenClient cdxgenClient;
    @InjectMock
    VulnerabilityReporting reporter;
    @InjectMock
    DependencyTrackClient dtrackClient;
    @Inject
    AnalysisProcessHandler cut;

    @Test
    @SuppressWarnings("rawtypes")
    void testSuccessfulProcessToDefaultBranch() throws IOException {
        // Given
        var repoUrl = "heubeck/examiner";
        var branch = "main";

        var projectName = "examiner";
        var ghRepo = spy(GitHub.connectAnonymously().getRepository(repoUrl));

        when(reporter.createVulnerabilityReport(any(), eq(projectName))).thenReturn(Uni.createFrom().item(Result.success(VulnerabilityReporting.VulnerabilityReport.report("la di dum", projectName))));
        var captor = ArgumentCaptor.forClass(RepositoryDetails.class);
        when(dtrackClient.uploadSBOM(captor.capture(), any(), eq(projectName), any()))
            .thenReturn(Uni.createFrom().item(Result.success(Project.available("http://project/yehaaa", "yehaaa"))));

        GHPullRequest pr = mock(GHPullRequest.class);
        GHIssueCommentQueryBuilder cqb = mock(GHIssueCommentQueryBuilder.class);
        PagedIterable pi = mock(PagedIterable.class);
        GHIssueComment newComment = mock(GHIssueComment.class);

        GHCommitPointer head = mock(GHCommitPointer.class);
        when(head.getRef()).thenReturn("main");
        GHCommitPointer base = mock(GHCommitPointer.class);
        when(base.getRef()).thenReturn("main");
        when(pr.getHead()).thenReturn(head);
        when(pr.getBase()).thenReturn(base);
        when(pr.queryComments()).thenReturn(cqb);
        when(cqb.list()).thenReturn(pi);
        when(pi.spliterator()).thenReturn(Spliterators.emptySpliterator());
        when(pr.comment(endsWith("[//]: # (Technolinator)"))).thenReturn(newComment);
        GHEventPayload.PullRequest prPayload = mock(GHEventPayload.PullRequest.class);
        when(prPayload.getRepository()).thenReturn(ghRepo);
        when(prPayload.getNumber()).thenReturn(42);
        when(prPayload.getPullRequest()).thenReturn(pr);
        when(ghRepo.getPullRequests(eq(GHIssueState.OPEN))).thenReturn(List.of(pr));

        var metadata = new Command.Metadata(branch, repoUrl, "", Optional.empty());

        var event = new PullRequestEvent(
            prPayload,
            Optional.empty()
        );

        // When
        await(cut.onPullRequest(event, metadata));

        // Then
        verify(repoService).createCheckoutCommand(any(), any());
        verify(cdxgenClient).createCommands(any(), eq(projectName), eq(false), eq(Optional.empty()));
        verify(reporter).createVulnerabilityReport(any(), eq(projectName));
        verify(dtrackClient).uploadSBOM(any(), any(), eq(projectName), any());
        verify(newComment).getHtmlUrl();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void testSuccessfulProcessToArbitraryBranch() throws IOException {
        // Given
        var repoUrl = "heubeck/examiner";
        var branch = "main";

        var projectName = "examiner";
        var ghRepo = spy(GitHub.connectAnonymously().getRepository(repoUrl));

        when(reporter.createVulnerabilityReport(any(), eq(projectName))).thenReturn(Uni.createFrom().item(Result.success(VulnerabilityReporting.VulnerabilityReport.report("la di dum", projectName))));

        GHPullRequest pr = mock(GHPullRequest.class);
        GHIssueCommentQueryBuilder cqb = mock(GHIssueCommentQueryBuilder.class);
        PagedIterable pi = mock(PagedIterable.class);
        GHIssueComment newComment = mock(GHIssueComment.class);

        GHCommitPointer head = mock(GHCommitPointer.class);
        when(head.getRef()).thenReturn("technolinator-test");
        GHCommitPointer base = mock(GHCommitPointer.class);
        when(base.getRef()).thenReturn("main");
        when(pr.getHead()).thenReturn(head);
        when(pr.getBase()).thenReturn(base);
        when(pr.queryComments()).thenReturn(cqb);
        when(cqb.list()).thenReturn(pi);
        when(pi.spliterator()).thenReturn(Spliterators.emptySpliterator());
        when(pr.comment(endsWith("[//]: # (Technolinator)"))).thenReturn(newComment);
        GHEventPayload.PullRequest prPayload = mock(GHEventPayload.PullRequest.class);
        when(prPayload.getRepository()).thenReturn(ghRepo);
        when(prPayload.getNumber()).thenReturn(42);
        when(prPayload.getPullRequest()).thenReturn(pr);
        when(ghRepo.getPullRequests(eq(GHIssueState.OPEN))).thenReturn(List.of(pr));

        var metadata = new Command.Metadata(branch, repoUrl, "", Optional.empty());

        var event = new PullRequestEvent(
            prPayload,
            Optional.empty()
        );

        // When
        await(cut.onPullRequest(event, metadata));

        // Then
        verify(repoService).createCheckoutCommand(any(), any());
        verify(cdxgenClient).createCommands(any(), eq(projectName), eq(false), eq(Optional.empty()));
        verify(reporter).createVulnerabilityReport(any(), eq(projectName));
        verifyNoInteractions(dtrackClient);
        verify(newComment).getHtmlUrl();
    }
}
