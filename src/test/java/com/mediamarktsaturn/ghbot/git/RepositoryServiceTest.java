package com.mediamarktsaturn.ghbot.git;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.MalformedURLException;
import java.net.URL;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class RepositoryServiceTest {

    @Inject
    RepositoryService cut;

    @ParameterizedTest
    @CsvSource({
        "https://github.com/heubeck/examiner, main",
        "https://github.com/heubeck/examiner.git, main",
        "https://github.com/jug-in/jug-in.bayern.git, master",
        "https://github.com/jug-in/jug-in.bayern, gh-pages"
    })
    public void testSuccessfulCheckout(URL repoUrl, String branch) {
        // When
        var result = await(cut.checkoutBranch(repoUrl, branch));

        // Then
        assertThat(result).isInstanceOfSatisfying(RepositoryService.CheckoutResult.Success.class, success -> {
            final LocalRepository repo = success.repo();
            try (repo) {
                assertThat(repo.dir()).exists();
                assertThat(repo.repo().getRepository().getBranch()).isEqualTo(branch);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            await().untilAsserted(() -> assertThat(repo.dir()).doesNotExist());
        });
    }

    @Test
    public void testInvalidRepository() throws MalformedURLException {
        // Given
        var repoUrl = new URL("https://github.com/there-s-no-such-organization/no-repository-here");
        var branch = "never";

        // When
        var result = await(cut.checkoutBranch(repoUrl, branch));

        // Then
        assertThat(result).isInstanceOfSatisfying(RepositoryService.CheckoutResult.Failure.class, failure -> {
            // depending on the provided GH token, there are different errors possible
            assertThat(failure.cause().toString()).containsAnyOf(
                "org.eclipse.jgit.api.errors.InvalidRemoteException: Invalid remote: origin",
                "org.eclipse.jgit.api.errors.TransportException: https://github.com/there-s-no-such-organization/no-repository-here: not authorized");
        });
    }

    @Test
    public void testInvalidBranch() throws MalformedURLException {
        // Given
        var repoUrl = new URL("https://github.com/heubeck/examiner");
        var branch = "never/ever";

        // When
        var result = await(cut.checkoutBranch(repoUrl, branch));

        // Then
        assertThat(result).isInstanceOfSatisfying(RepositoryService.CheckoutResult.Failure.class, failure -> {
            assertThat(failure.cause().toString()).isEqualTo("org.eclipse.jgit.api.errors.RefNotFoundException: Ref origin/never/ever cannot be resolved");
        });
    }

}
