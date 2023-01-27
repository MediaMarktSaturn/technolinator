package com.mediamarktsaturn.ghbot.handler;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static com.mediamarktsaturn.ghbot.TestUtil.ignore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

import org.cyclonedx.model.Bom;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.git.LocalRepository;
import com.mediamarktsaturn.ghbot.git.RepositoryService;
import com.mediamarktsaturn.ghbot.sbom.CdxgenClient;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.smallrye.mutiny.Uni;

@QuarkusTest
public class PushHandlerTest {

    @InjectMock
    RepositoryService repoService;
    @InjectMock
    CdxgenClient cdxgenClient;
    @InjectMock
    DependencyTrackClient dtrackClient;
    @Inject
    PushHandler cut;

    @Test
    public void testSuccessfulProcess() throws IOException {
        // Given
        var repoUrl = new URL("https://github.com/heubeck/examiner");
        var branch = "main";
        // create a temporary copy of the repo/maven test resource as it will be cleaned up by the process
        var tmpFile = Files.createTempDirectory("technolinator").toFile();
        var pom = new File("src/test/resources/repo/maven", "pom.xml");
        Files.copy(pom.toPath(), Path.of(tmpFile.getAbsolutePath(), "pom.xml"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

        var sbom = new Bom();
        var version = "test-version";
        var projectName = "examiner";

        when(repoService.checkoutBranch(any()))
            .thenReturn(
                Uni.createFrom().item(
                    new RepositoryService.CheckoutResult.Success(
                        new LocalRepository(tmpFile)
                    )
                )
            );

        when(cdxgenClient.generateSBOM(tmpFile, projectName, Optional.empty()))
            .thenReturn(
                Uni.createFrom().item(
                    new CdxgenClient.SBOMGenerationResult.Proper(
                        sbom, "test-group", projectName, version, List.of()
                    )
                )
            );

        when(dtrackClient.uploadSBOM(projectName, version, sbom))
            .thenReturn(
                Uni.createFrom().item(
                    new DependencyTrackClient.UploadResult.Success("")
                )
            );

        GHRepository ghRepo = mock(GHRepository.class);
        when(ghRepo.getUrl()).thenReturn(repoUrl);
        when(ghRepo.getDefaultBranch()).thenReturn(branch);

        GHEventPayload.Push pushPayload = mock(GHEventPayload.Push.class);
        when(pushPayload.getRepository()).thenReturn(ghRepo);
        when(pushPayload.getRef()).thenReturn("refs/heads/" + branch);

        var event = new PushEvent(
            pushPayload,
            ignore(),
            Optional.empty()
        );

        // When
        await(cut.onPush(event));

        // Then
        verify(repoService).checkoutBranch(any());
        verify(cdxgenClient).generateSBOM(tmpFile, projectName, Optional.empty());
        verify(dtrackClient).uploadSBOM(projectName, branch, sbom);
        await().untilAsserted(() -> {
            assertThat(tmpFile).doesNotExist();
        });
    }
}
