package com.mediamarktsaturn.technolinator.git;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class RepositoryServiceTest {

    @Inject
    RepositoryService cut;

    @Test
    void testCommandCreation() {
        // Given
        var ghRepo = mock(GHRepository.class);
        when(ghRepo.getName()).thenReturn("examiner");

        // When
        var cmd = cut.createCheckoutCommand(ghRepo, "refs/heads/main");

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
                assertThat(repo.dir().resolve(checkFile)).exists();
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
            assertThat(failure.cause().toString()).hasToString("org.kohsuke.github.GHFileNotFoundException: https://api.github.com/repos/heubeck/examiner/zipball/never/ever 404: Not Found");
        });
    }

}
