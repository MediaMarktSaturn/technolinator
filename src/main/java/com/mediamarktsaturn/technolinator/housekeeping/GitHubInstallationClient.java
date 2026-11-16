package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkiverse.githubapp.GitHubClientProvider;
import org.kohsuke.github.GitHub;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Provides a GitHub client authenticated as an installation of the GitHub app, which is guaranteed to be valid for a while.
 * <p>
 * Installation clients are created with a fixed installation token, which the GitHub app extension caches
 * until 10 minutes before it expires. So a client is guaranteed to be valid for 10 minutes after creation,
 * and is replaced after {@link #MAX_AGE}, leaving at least 5 minutes for the user of the returned client.
 * A housekeeping run takes much longer than a token is valid, so the clients can't be kept for the whole run.
 */
public final class GitHubInstallationClient {

    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final long installationId;
    private final GitHubClientProvider clientProvider;
    private final Clock clock;

    private GitHub github;
    private Instant createdAt;

    GitHubInstallationClient(long installationId, GitHubClientProvider clientProvider, Clock clock) {
        this.installationId = installationId;
        this.clientProvider = clientProvider;
        this.clock = clock;
    }

    public long installationId() {
        return installationId;
    }

    /**
     * @return a client valid for at least 5 more minutes. Anything running longer needs to call this again.
     */
    public synchronized GitHub current() {
        var now = clock.instant();
        if (github == null || !now.isBefore(createdAt.plus(MAX_AGE))) {
            github = clientProvider.getInstallationClient(installationId);
            createdAt = now;
        }
        return github;
    }
}
