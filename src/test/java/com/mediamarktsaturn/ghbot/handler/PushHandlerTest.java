package com.mediamarktsaturn.ghbot.handler;


import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import com.mediamarktsaturn.ghbot.sbom.Project;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.Result;
import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.git.RepositoryService;
import com.mediamarktsaturn.ghbot.sbom.CdxgenClient;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;
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

        when(dtrackClient.uploadSBOM(eq(projectName), eq(branch), any()))
            .thenReturn(Uni.createFrom().item(Result.success(Project.available("yehaaa"))));

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
        verify(dtrackClient).uploadSBOM(eq(projectName), eq(branch), any());
    }
}
