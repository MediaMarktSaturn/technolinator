package com.mediamarktsaturn.technolinator.handler;


import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import com.mediamarktsaturn.technolinator.sbom.Project;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.mutiny.Uni;

@QuarkusTest
class PushHandlerTest {

    @InjectSpy
    RepositoryService repoService;
    @InjectSpy
    CdxgenClient cdxgenClient;
    @InjectMock
    DependencyTrackClient dtrackClient;
    @Inject
    PushHandler cut;

    @Test
    void testSuccessfulProcess() throws IOException {
        // Given
        var repoUrl = "heubeck/examiner";
        var branch = "main";

        var projectName = "examiner";
        var ghRepo = GitHub.connectAnonymously().getRepository(repoUrl);

        when(dtrackClient.uploadSBOM(eq(projectName), eq(branch), any(), any(), any(), eq("https://github.com/heubeck/examiner")))
            .thenReturn(Uni.createFrom().item(Result.success(Project.available("http://project/yehaaa", "yehaaa"))));

        GHEventPayload.Push pushPayload = mock(GHEventPayload.Push.class);
        when(pushPayload.getRepository()).thenReturn(ghRepo);
        when(pushPayload.getRef()).thenReturn("refs/heads/" + branch);

        var metadata = new Command.Metadata(branch, repoUrl, "", Optional.empty());

        var event = new PushEvent(
            pushPayload,
            Optional.empty()
        );

        // When
        await(cut.onPush(event, metadata));

        // Then
        verify(repoService).createCheckoutCommand(any());
        verify(cdxgenClient).createCommand(any(), eq(projectName), eq(Optional.empty()));
        verify(dtrackClient).uploadSBOM(eq(projectName), eq(branch), any(), any(), any(), eq("https://github.com/heubeck/examiner"));
    }
}
