package com.mediamarktsaturn.ghbot.git;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.Result;
import com.mediamarktsaturn.ghbot.events.PushEvent;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class RepositoryServiceTest {

    @Inject
    RepositoryService cut;

    @Test
    void testCommandCreation() {
        // Given
        var pushEvent = mock(GHEventPayload.Push.class);
        var ghRepo = mock(GHRepository.class);
        when(ghRepo.getName()).thenReturn("examiner");
        when(pushEvent.getRepository()).thenReturn(ghRepo);
        when(pushEvent.getRef()).thenReturn("refs/heads/main");
        var event = new PushEvent(
            pushEvent,
            Optional.empty()
        );

        // When
        var cmd = cut.createCheckoutCommand(event);

        // Then
        assertThat(cmd.repositoryName()).hasToString("examiner");
        assertThat(cmd.repository()).isSameAs(ghRepo);
        assertThat(cmd.reference()).hasToString("refs/heads/main");
    }

    @ParameterizedTest
    @CsvSource({
        "heubeck/examiner, refs/heads/main, pom.xml",
        "jug-in/jug-in.bayern, refs/heads/master, README.md",
        "jug-in/jug-in.bayern, refs/heads/gh-pages, index.html"
    })
    void testSuccessfulCheckout(String repoName, String branch, String checkFile) throws IOException {
        // Given
        var ghRepo = GitHub.connectAnonymously().getRepository(repoName);
        var metadata = new Command.Metadata("main", "heubeck/examiner", "", Optional.empty());

        var cmd = new RepositoryService.CheckoutCommand(ghRepo, branch);

        // When
        var result = await(cmd.execute(metadata));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            final LocalRepository repo = (LocalRepository) success.result();
            try (repo) {
                assertThat(repo.dir()).exists();
                assertThat(new File(repo.dir(), checkFile)).exists();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            await().untilAsserted(() -> assertThat(repo.dir()).doesNotExist());
        });
    }

    @Test
    void testInvalidBranch() throws IOException {
        // Given
        var ghRepo = GitHub.connectAnonymously().getRepository("heubeck/examiner");
        var metadata = new Command.Metadata("main", "heubeck/examiner", "", Optional.empty());

        var cmd = new RepositoryService.CheckoutCommand(ghRepo, "never/ever");

        // When
        var result = await(cmd.execute(metadata));


        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Failure.class, failure -> {
            assertThat(failure.cause().toString()).isEqualTo("org.kohsuke.github.GHFileNotFoundException: https://api.github.com/repos/heubeck/examiner/zipball/never/ever 404: Not Found");
        });
    }

}
