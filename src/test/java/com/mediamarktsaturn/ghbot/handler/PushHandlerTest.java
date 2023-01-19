package com.mediamarktsaturn.ghbot.handler;

import static com.mediamarktsaturn.ghbot.TestUtil.ignore;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;

import org.cyclonedx.model.Bom;
import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.git.LocalRepository;
import com.mediamarktsaturn.ghbot.git.RepositoryService;
import com.mediamarktsaturn.ghbot.sbom.CdxgenClient;
import com.mediamarktsaturn.ghbot.sbom.DependencyTrackClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

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
    public void testSuccessfulProcess() throws MalformedURLException {
        // Given
        var repoUrl = new URL("https://github.com/heubeck/examiner");
        var branch = "main";
        var dir = new File("src/test/resources/repo/maven");
        var sbom = new Bom();
        var version = "test-version";
        var projectName = "examiner";

        when(repoService.checkoutBranch(repoUrl, branch))
            .thenReturn(
                CompletableFuture.completedFuture(
                    new RepositoryService.CheckoutResult.Success(
                        new LocalRepository(null, dir)
                    )
                )
            );

        when(cdxgenClient.generateSBOM(dir, Optional.empty()))
            .thenReturn(
                CompletableFuture.completedFuture(
                    new CdxgenClient.SBOMGenerationResult.Proper(
                        sbom, "test-group", "test-name", version, List.of()
                    )
                )
            );

        when(dtrackClient.uploadSBOM(projectName, version, sbom))
            .thenReturn(
                CompletableFuture.completedFuture(
                    new DependencyTrackClient.UploadResult.Success("")
                )
            );

        var event = new PushEvent(
            repoUrl,
            "refs/heads/" + branch,
            branch,
            ignore(),
            Optional.empty()
        );

        // When
        cut.onPush(event);

        // Then
        verify(repoService).checkoutBranch(repoUrl, branch);
        verify(cdxgenClient).generateSBOM(dir, Optional.empty());
        verify(dtrackClient).uploadSBOM(projectName, branch, sbom);
    }
}
