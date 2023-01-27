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
import org.kohsuke.github.GitHub;

import com.mediamarktsaturn.ghbot.events.PushEvent;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class RepositoryServiceTest {

    @Inject
    RepositoryService cut;

    @ParameterizedTest
    @CsvSource({
        "heubeck/examiner, refs/heads/main, pom.xml",
        "jug-in/jug-in.bayern, refs/heads/master, README.md",
        "jug-in/jug-in.bayern, refs/heads/gh-pages, index.html"
    })
    public void testSuccessfulCheckout(String repoName, String branch, String checkFile) throws IOException {
        // When
        var ghRepo = GitHub.connectAnonymously().getRepository(repoName);
        var pushEvent = mock(GHEventPayload.Push.class);
        when(pushEvent.getRepository()).thenReturn(ghRepo);
        when(pushEvent.getRef()).thenReturn(branch);
        var event = new PushEvent(
            pushEvent,
            Optional.empty()
        );
        var result = await(cut.checkoutBranch(event));

        // Then
        assertThat(result).isInstanceOfSatisfying(RepositoryService.CheckoutResult.Success.class, success -> {
            final LocalRepository repo = success.repo();
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
    public void testInvalidBranch() throws IOException {
        // Given
        var ghRepo = GitHub.connectAnonymously().getRepository("heubeck/examiner");
        var pushEvent = mock(GHEventPayload.Push.class);
        when(pushEvent.getRepository()).thenReturn(ghRepo);
        when(pushEvent.getRef()).thenReturn("never/ever");
        var event = new PushEvent(
            pushEvent,
            Optional.empty()
        );

        // When
        var result = await(cut.checkoutBranch(event));

        // Then
        assertThat(result).isInstanceOfSatisfying(RepositoryService.CheckoutResult.Failure.class, failure -> {
            assertThat(failure.cause().toString()).isEqualTo("org.kohsuke.github.GHFileNotFoundException: https://api.github.com/repos/heubeck/examiner/zipball/never/ever 404: Not Found");
        });
    }

}
