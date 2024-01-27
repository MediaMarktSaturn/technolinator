package com.mediamarktsaturn.technolinator.handler;


import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.PushEvent;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.SbomqsClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;
import org.mockito.ArgumentCaptor;
import org.testcontainers.shaded.org.hamcrest.CoreMatchers;

import java.io.IOException;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class PushHandlingTest {

    @InjectSpy
    RepositoryService repoService;
    @InjectSpy
    CdxgenClient cdxgenClient;
    @InjectSpy
    SbomqsClient sbomqsClient;
    @InjectMock
    DependencyTrackClient dtrackClient;
    @Inject
    AnalysisProcessHandler cut;

    @Test
    void testSuccessfulProcess() throws IOException {
        // Given
        var repoUrl = "heubeck/examiner";
        var branch = "main";

        var projectName = "examiner";
        var ghRepo = GitHub.connectAnonymously().getRepository(repoUrl);
        var captor = ArgumentCaptor.forClass(RepositoryDetails.class);

        when(dtrackClient.uploadSBOM(captor.capture(), any(), eq(projectName), any(), any()))
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
        verify(repoService).createCheckoutCommand(any(), any());
        verify(cdxgenClient).createCommands(any(), eq(projectName), eq(true), eq(Optional.empty()));
        verify(sbomqsClient).calculateQualityScore(any());
        verify(dtrackClient).uploadSBOM(any(), any(), eq(projectName), any(), eq(Optional.empty()));

        assertThat(captor.getValue()).isNotNull().satisfies(repoDetails -> {
            assertThat(repoDetails.name()).hasToString(projectName);
            assertThat(repoDetails.version()).hasToString(branch);
            assertThat(repoDetails.websiteUrl()).hasToString("https://github.com/heubeck/examiner");
            assertThat(repoDetails.vcsUrl()).hasToString("git://github.com/heubeck/examiner.git");
            assertThat(repoDetails.description()).isNotBlank();
            assertThat(repoDetails.topics())
                .hasSizeGreaterThan(1)
                .anyMatch(t -> t.startsWith("sbom-quality-score="));
        });
    }
}
