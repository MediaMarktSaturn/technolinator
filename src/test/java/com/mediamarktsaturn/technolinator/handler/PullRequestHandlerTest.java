package com.mediamarktsaturn.technolinator.handler;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;
import java.util.Spliterators;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueCommentQueryBuilder;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PullRequestEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.GrypeClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

@QuarkusTest
class PullRequestHandlerTest {

    @InjectSpy
    RepositoryService repoService;
    @InjectSpy
    CdxgenClient cdxgenClient;
    @InjectMock
    GrypeClient grypeClient;
    @Inject
    PullRequestHandler cut;

    @Test
    void testSuccessfulProcess() throws IOException {
        // Given
        var repoUrl = "heubeck/examiner";
        var branch = "main";

        var projectName = "examiner";
        var ghRepo = GitHub.connectAnonymously().getRepository(repoUrl);

        when(grypeClient.createVulnerabilityReport(any())).thenReturn(Uni.createFrom().item(Result.success(GrypeClient.VulnerabilityReport.report("la di dum"))));

        GHPullRequest pr = mock(GHPullRequest.class);
        GHIssueCommentQueryBuilder cqb = mock(GHIssueCommentQueryBuilder.class);
        PagedIterable pi = mock(PagedIterable.class);
        GHIssueComment newComment = mock(GHIssueComment.class);

        GHCommitPointer head = mock(GHCommitPointer.class);
        when(head.getRef()).thenReturn("main");
        when(pr.getHead()).thenReturn(head);
        when(pr.queryComments()).thenReturn(cqb);
        when(cqb.list()).thenReturn(pi);
        when(pi.spliterator()).thenReturn(Spliterators.emptySpliterator());
        when(pr.comment(endsWith("[//]: # (Technolinator)"))).thenReturn(newComment);
        GHEventPayload.PullRequest prPayload = mock(GHEventPayload.PullRequest.class);
        when(prPayload.getRepository()).thenReturn(ghRepo);
        when(prPayload.getNumber()).thenReturn(42);
        when(prPayload.getPullRequest()).thenReturn(pr);

        var metadata = new Command.Metadata(branch, repoUrl, "", Optional.empty());

        var event = new PullRequestEvent(
            prPayload,
            Optional.empty()
        );

        // When
        await(cut.onPullRequest(event, metadata));

        // Then
        verify(repoService).createCheckoutCommand(any(), any());
        verify(cdxgenClient).createCommand(any(), eq(projectName), eq(false), eq(Optional.empty()));
        verify(grypeClient).createVulnerabilityReport(any());
        verify(newComment).getHtmlUrl();
    }
}
