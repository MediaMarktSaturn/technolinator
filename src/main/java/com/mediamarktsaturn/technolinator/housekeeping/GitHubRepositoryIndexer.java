package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHRepositorySelection;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * Creates a {@link GitHubRepositoryIndex} from all installations of the GitHub app, using the very same app credentials as the event handling.
 * Only lists the repositories of the installations, without looking into any of them.
 */
@ApplicationScoped
public class GitHubRepositoryIndexer {

    private final GitHubClientProvider clientProvider;
    private final int installationConcurrency;
    private final Clock clock;

    @Inject
    public GitHubRepositoryIndexer(
        GitHubClientProvider clientProvider,
        @ConfigProperty(name = "app.housekeeping.installation_concurrency") int installationConcurrency
    ) {
        this(clientProvider, installationConcurrency, Clock.systemUTC());
    }

    GitHubRepositoryIndexer(GitHubClientProvider clientProvider, int installationConcurrency, Clock clock) {
        this.clientProvider = clientProvider;
        this.installationConcurrency = Math.max(1, installationConcurrency);
        this.clock = clock;
    }

    /**
     * Fails if the installations of the app can't be listed. Installations whose repositories can't be listed are logged, counted
     * as {@link GitHubRepositoryIndex#failedInstallations()} and left out, so the repositories of their accounts are reported as
     * {@link GitHubRepositoryIndex.State#UNKNOWN}.
     */
    public Uni<GitHubRepositoryIndex> build(HousekeepingRun run) {
        var builder = GitHubRepositoryIndex.builder();
        return Uni.createFrom().item(Unchecked.supplier(() -> listInstallations(run)))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onItem().transformToMulti(installations -> Multi.createFrom().iterable(installations))
            .onItem().transformToUni(installation -> indexInstallation(run, installation, builder))
            .merge(installationConcurrency)
            .onItem().ignoreAsUni()
            .map(ignored -> builder.build());
    }

    private List<GHAppInstallation> listInstallations(HousekeepingRun run) throws IOException {
        run.writeToMDC();
        var installations = clientProvider.getApplicationClient().getApp().listInstallations().toList();
        Log.infof("Indexing repositories of %d installations of the GitHub app", installations.size());
        return installations.stream()
            .filter(installation -> {
                if (installation.getSuspendedAt() != null) {
                    Log.infof("Skipping suspended installation %d", installation.getId());
                    return false;
                }
                return true;
            })
            .toList();
    }

    private Uni<Void> indexInstallation(HousekeepingRun run, GHAppInstallation installation, GitHubRepositoryIndex.Builder builder) {
        final var installationId = installation.getId();
        return Uni.createFrom().item(Unchecked.supplier(() -> {
                run.writeToMDC();
                var account = installation.getAccount();
                if (account == null || account.getLogin() == null) {
                    throw new IllegalStateException("installation doesn't tell its account");
                }
                var login = account.getLogin();
                var host = installation.getHtmlUrl().getHost();
                var client = new GitHubInstallationClient(installationId, clientProvider, clock);
                // repositories need to be listed with the installation's own client, the application client lacks the permission
                var repositories = client.current().getInstallation().listRepositories().toList().stream()
                    .map(repository -> GitHubRepo.of(host, login, repository, client))
                    .toList();
                var all = installation.getRepositorySelection() == GHRepositorySelection.ALL;
                builder.addInstallation(host, login, all, repositories);
                Log.debugf("Installation %d of %s grants access to %d repositories (%s)", installationId, login, repositories.size(), all ? "all" : "selected");
                return (Void) null;
            }))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onFailure().invoke(failure -> {
                builder.installationFailed();
                run.writeToMDC();
                Log.errorf(failure, "Could not index repositories of installation %d, its repositories are treated as unknown", installationId);
            })
            .onFailure().recoverWithNull();
    }
}
