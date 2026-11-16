package com.mediamarktsaturn.technolinator;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.GitHubRateLimitHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public interface TestUtil {

    static <T> T await(Uni<T> uni) {
        UniAssertSubscriber<T> uas = new UniAssertSubscriber<>();
        uni
            .onFailure().invoke(failure -> {
                Logger.getAnonymousLogger().log(Level.SEVERE, "Await failed", failure);
            })
            .subscribe().withSubscriber(uas);
        uas.awaitItem(Duration.ofMinutes(15));
        return uas.getItem();
    }

    static <T> Consumer<T> ignore() {
        return any -> {
        };
    }

    /**
     * GitHub client for tests reading public repositories: authenticated if a {@code GITHUB_TOKEN} is provided, as the
     * unauthenticated rate limit is quickly exhausted on shared CI runners. In case of exhaustion the tests fail instead of
     * sleeping until the limit is reset, which would hang the whole test run.
     */
    static GitHub github() throws IOException {
        var builder = new GitHubBuilder().withRateLimitHandler(GitHubRateLimitHandler.FAIL);
        var token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            builder.withOAuthToken(token.trim());
        }
        return builder.build();
    }

    static URL url(String url) {
        try {
            return URI.create(url).toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
