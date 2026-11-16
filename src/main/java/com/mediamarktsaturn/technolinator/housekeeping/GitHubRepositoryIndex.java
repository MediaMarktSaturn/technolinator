package com.mediamarktsaturn.technolinator.housekeeping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot of what the installations of the GitHub app know about the repositories of their accounts.
 * <p>
 * Lists the repositories and answers whether a repository exists, is archived, or is gone. Gone is only reported if that's certain:
 * <ul>
 *     <li>the account of the repository has an installation, that could be listed</li>
 *     <li>the installation grants access to <em>all</em> repositories of the account, otherwise a repository
 *     not being listed might just have been removed from the selection of the installation</li>
 * </ul>
 * In any other case, e.g. for accounts without installation, the state is {@link State#UNKNOWN}.
 * <p>
 * Instead of being built from complete listings, an index might consist of single repositories looked at on their own (see
 * {@link Builder#addRepository} and {@link Builder#addGoneRepository}), which only tell about themselves.
 */
public final class GitHubRepositoryIndex {

    public enum State {
        /**
         * The repository exists and isn't archived
         */
        ACTIVE,
        /**
         * The repository exists, but is archived
         */
        ARCHIVED,
        /**
         * The repository doesn't exist (anymore)
         */
        GONE,
        /**
         * Nothing can be said about the repository
         */
        UNKNOWN
    }

    private final Map<String, Owner> owners;
    private final List<GitHubRepo> repositories;
    private final int failedInstallations;

    private GitHubRepositoryIndex(Map<String, Owner> owners, List<GitHubRepo> repositories, int failedInstallations) {
        this.owners = owners;
        this.repositories = repositories;
        this.failedInstallations = failedInstallations;
    }

    public State lookup(RepositoryReference repository) {
        var owner = owners.get(repository.ownerKey());
        if (owner == null) {
            return State.UNKNOWN;
        }
        var found = owner.repositories.get(repository.name());
        if (found == null) {
            return owner.exhaustive || owner.gone.contains(repository.name()) ? State.GONE : State.UNKNOWN;
        }
        return found.archived() ? State.ARCHIVED : State.ACTIVE;
    }

    /**
     * @return all repositories of all installations, ordered by their full name
     */
    public List<GitHubRepo> repositories() {
        return repositories;
    }

    /**
     * @return number of accounts covered by the snapshot
     */
    public int owners() {
        return owners.size();
    }

    /**
     * @return number of installations whose repositories couldn't be listed, so they're not covered by the snapshot
     */
    public int failedInstallations() {
        return failedInstallations;
    }

    public static Builder builder() {
        return new Builder();
    }

    private record Owner(boolean exhaustive, Map<String, GitHubRepo> repositories, Set<String> gone) {
    }

    public static final class Builder {
        private final Map<String, Owner> owners = new HashMap<>();
        private int failedInstallations;

        private Builder() {
        }

        /**
         * Adds the repositories an installation grants access to.
         *
         * @param host         Host of the GitHub instance the account lives at
         * @param owner        Login of the account (user or organization) the installation belongs to
         * @param allGranted   Whether the installation is granted access to all repositories of the account
         * @param repositories The repositories the installation lists
         */
        public synchronized Builder addInstallation(String host, String owner, boolean allGranted, List<GitHubRepo> repositories) {
            var byName = new HashMap<String, GitHubRepo>();
            repositories.forEach(repository -> byName.put(repository.reference().name(), repository));
            // an installation not listing anything at all is more likely to be a glitch than an account without repositories
            var exhaustive = allGranted && !byName.isEmpty();
            var key = new RepositoryReference(host.toLowerCase(Locale.ROOT), owner.toLowerCase(Locale.ROOT), "").ownerKey();
            owners.put(key, new Owner(exhaustive, Map.copyOf(byName), Set.of()));
            return this;
        }

        /**
         * Adds a single repository, that was looked at on its own instead of listing all repositories of its account.
         * Doesn't make the account's repositories exhaustive, so other repositories of it stay {@link State#UNKNOWN}.
         */
        public synchronized Builder addRepository(String host, String owner, GitHubRepo repository) {
            var key = ownerKey(host, owner);
            var existing = owners.get(key);
            var repositories = new HashMap<String, GitHubRepo>(existing == null ? Map.of() : existing.repositories());
            repositories.put(repository.reference().name(), repository);
            owners.put(key, new Owner(existing != null && existing.exhaustive(), Map.copyOf(repositories), existing == null ? Set.of() : existing.gone()));
            return this;
        }

        /**
         * Registers a repository that was looked at on its own and is certainly gone, e.g. because the installation having access to
         * all repositories of the account gets a 404 for it. Other repositories of the account stay {@link State#UNKNOWN}.
         */
        public synchronized Builder addGoneRepository(String host, String owner, String name) {
            var key = ownerKey(host, owner);
            var existing = owners.get(key);
            var gone = new HashSet<String>(existing == null ? Set.of() : existing.gone());
            gone.add(name.toLowerCase(Locale.ROOT));
            owners.put(key, new Owner(existing != null && existing.exhaustive(), existing == null ? Map.of() : existing.repositories(), Set.copyOf(gone)));
            return this;
        }

        private static String ownerKey(String host, String owner) {
            return new RepositoryReference(host.toLowerCase(Locale.ROOT), owner.toLowerCase(Locale.ROOT), "").ownerKey();
        }

        /**
         * Registers an installation, whose repositories couldn't be listed
         */
        public synchronized Builder installationFailed() {
            failedInstallations++;
            return this;
        }

        public synchronized GitHubRepositoryIndex build() {
            var all = new ArrayList<GitHubRepo>();
            owners.values().forEach(owner -> all.addAll(owner.repositories().values()));
            all.sort(Comparator.comparing(GitHubRepo::fullName));
            return new GitHubRepositoryIndex(Map.copyOf(owners), List.copyOf(all), failedInstallations);
        }
    }
}
