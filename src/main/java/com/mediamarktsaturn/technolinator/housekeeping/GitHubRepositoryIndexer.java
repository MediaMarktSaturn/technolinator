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
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySelection;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creates a {@link GitHubRepositoryIndex} from all installations of the GitHub app, using the very same app credentials as the event handling.
 * Only lists the repositories of the installations, without looking into any of them.
 */
@ApplicationScoped
public class GitHubRepositoryIndexer {

    /**
     * Maximum page size of GitHub, saves requests of the rate limit (5000 per hour and installation, more for bigger ones) for big installations
     */
    private static final int LIST_PAGE_SIZE = 100;

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
        var installations = activeInstallations();
        Log.infof("Indexing repositories of %d installations of the GitHub app", installations.size());
        return installations;
    }

    /**
     * Looks at the given repositories only, instead of listing all repositories of all installations: lists the installations
     * of the app (which is cheap), and fetches each of the repositories on its own with the installation of its account.
     * <p>
     * A repository is {@link GitHubRepositoryIndex.State#GONE} if its account has an installation with access to <em>all</em> repositories
     * and GitHub doesn't know the repository.
     * Repositories renamed within their account (GitHub redirects them) are {@link GitHubRepositoryIndex.State#MOVED}, and transferred ones
     * are {@link GitHubRepositoryIndex.State#GONE}. Repositories of accounts without (active) installation, of installations restricted
     * to selected repositories, or that couldn't be fetched, are {@link GitHubRepositoryIndex.State#UNKNOWN}.
     * Fails if the installations of the app can't be listed.
     */
    public Uni<GitHubRepositoryIndex> build(HousekeepingRun run, Collection<Target> targets) {
        var builder = GitHubRepositoryIndex.builder();
        var targetsByOwner = new LinkedHashMap<String, List<Target>>();
        targets.forEach(target -> targetsByOwner.computeIfAbsent(target.owner(), owner -> new ArrayList<>()).add(target));

        return Uni.createFrom().item(Unchecked.supplier(() -> {
                run.writeToMDC();
                var installations = activeInstallations().stream()
                    .filter(installation -> installation.getAccount() != null && installation.getAccount().getLogin() != null
                        && targetsByOwner.containsKey(installation.getAccount().getLogin().toLowerCase(Locale.ROOT)))
                    .toList();
                var covered = installations.stream().map(installation -> installation.getAccount().getLogin().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                targetsByOwner.keySet().stream().filter(owner -> !covered.contains(owner))
                    .forEach(owner -> Log.infof("The GitHub app isn't installed for %s, so its repositories are treated as unknown", owner));
                return installations;
            }))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onItem().transformToMulti(installations -> Multi.createFrom().iterable(installations))
            .onItem().transformToUni(installation -> lookupRepositories(run, installation,
                targetsByOwner.get(installation.getAccount().getLogin().toLowerCase(Locale.ROOT)), builder))
            .merge(installationConcurrency)
            .onItem().ignoreAsUni()
            .map(ignored -> builder.build());
    }

    /**
     * Confirms that repositories, which aren't listed by their account's installation, really are gone, by looking them up on their own.
     * Listings don't tell about repositories that got renamed, which GitHub redirects, so these aren't gone, although they're missing.
     * Repositories transferred to another account are gone, as they don't belong to the account anymore.
     *
     * @return the candidates that certainly don't exist (anymore), and the ones renamed within their account together with their new repository.
     * Anything else (failing to be looked up) is left out
     */
    public Uni<Confirmation> confirmGone(HousekeepingRun run, Collection<RepositoryReference> candidates) {
        var targets = new LinkedHashSet<Target>();
        candidates.forEach(candidate -> TargetedListing.parseRepository(candidate.owner() + "/" + candidate.name()).ifPresent(targets::add));
        if (targets.isEmpty()) {
            return Uni.createFrom().item(new Confirmation(Set.of(), Map.of()));
        }
        return build(run, targets).map(index -> new Confirmation(
            candidates.stream()
                .filter(candidate -> index.lookup(candidate) == GitHubRepositoryIndex.State.GONE)
                .collect(Collectors.toUnmodifiableSet()),
            candidates.stream()
                .filter(candidate -> index.lookup(candidate) == GitHubRepositoryIndex.State.MOVED)
                .collect(Collectors.toUnmodifiableMap(Function.identity(), candidate -> index.movedTo(candidate).orElseThrow(), (first, second) -> first))));
    }

    /**
     * @param gone  repositories that certainly don't exist (anymore)
     * @param moved repositories that got renamed within their account, with the repository they're named now
     */
    public record Confirmation(Set<RepositoryReference> gone, Map<RepositoryReference, GitHubRepo> moved) {
    }

    private static String ownerOf(GHRepository repository) {
        var fullName = repository.getFullName();
        if (fullName != null && fullName.indexOf('/') > 0) {
            return fullName.substring(0, fullName.indexOf('/'));
        }
        return repository.getOwnerName();
    }

    private List<GHAppInstallation> activeInstallations() throws IOException {
        return clientProvider.getApplicationClient().getApp().listInstallations().toList().stream()
            .filter(installation -> {
                if (installation.getSuspendedAt() != null) {
                    Log.infof("Skipping suspended installation %d", installation.getId());
                    return false;
                }
                return true;
            })
            .toList();
    }

    private Uni<Void> lookupRepositories(HousekeepingRun run, GHAppInstallation installation, List<Target> targets, GitHubRepositoryIndex.Builder builder) {
        final var installationId = installation.getId();
        return Uni.createFrom().item(Unchecked.supplier(() -> {
                run.writeToMDC();
                var login = installation.getAccount().getLogin();
                var host = installation.getHtmlUrl().getHost();
                var all = installation.getRepositorySelection() == GHRepositorySelection.ALL;
                var client = new GitHubInstallationClient(installationId, clientProvider, clock);
                var failed = false;
                for (var target : targets) {
                    try {
                        var repository = client.current().getRepository(login + "/" + target.name());
                        var owner = ownerOf(repository);
                        if (owner != null && !login.equalsIgnoreCase(owner)) {
                            // GitHub redirects transferred repositories. They aren't of this account anymore, which is treated like being deleted
                            Log.infof("Repository %s/%s got transferred to %s, treating it as gone", login, target.name(), repository.getFullName());
                            builder.addGoneRepository(host, login, target.name());
                        } else if (target.name().equalsIgnoreCase(repository.getName())) {
                            builder.addRepository(host, login, GitHubRepo.of(host, login, repository, client));
                        } else {
                            // GitHub redirects renamed repositories. Nothing exists under the requested name anymore, but the repository is alive
                            // (and maybe not even archived), so it must not be reported as gone. The projects follow it, see the rename action.
                            Log.infof("Repository %s/%s got renamed to %s", login, target.name(), repository.getFullName());
                            builder.addMovedRepository(host, login, target.name(), GitHubRepo.of(host, login, repository, client));
                        }
                    } catch (GHFileNotFoundException e) {
                        if (all) {
                            builder.addGoneRepository(host, login, target.name());
                        }
                    } catch (IOException e) {
                        failed = true;
                        Log.errorf(e, "Could not look up repository %s/%s, treating it as unknown", login, target.name());
                    }
                }
                if (failed) {
                    builder.installationFailed();
                }
                return (Void) null;
            }))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onFailure().invoke(failure -> {
                builder.installationFailed();
                run.writeToMDC();
                Log.errorf(failure, "Could not look up repositories of installation %d, they are treated as unknown", installationId);
            })
            .onFailure().recoverWithNull();
    }

    /**
     * A repository to look at, identified by its account and name, both in lower case
     */
    public record Target(String owner, String name) {
        public Target {
            owner = owner.toLowerCase(Locale.ROOT);
            name = name.toLowerCase(Locale.ROOT);
        }
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
                var listing = client.current().getInstallation().listRepositories().withPageSize(LIST_PAGE_SIZE);
                var repositories = listing.toList().stream()
                    .map(repository -> GitHubRepo.of(host, login, repository, client))
                    .toList();
                // fewer repositories than GitHub announces mean the listing isn't complete, so nothing can be said about the account
                var announced = listing.getTotalCount();
                if (announced > repositories.size()) {
                    throw new IllegalStateException("installation lists only %d of %d repositories".formatted(repositories.size(), announced));
                }
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
