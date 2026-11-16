package com.mediamarktsaturn.technolinator.housekeeping;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.util.Locale;
import java.util.Objects;

/**
 * A repository of a GitHub account, that is covered by an installation of the GitHub app.
 *
 * @param reference    Host, owner and name in lower case, for comparison with references found elsewhere
 * @param name         Name of the repository (without owner), as it's used for naming Dependency-Track projects
 * @param fullName     Name including the owner
 * @param htmlUrl      Web URL of the repository, which Technolinator references as website of the Dependency-Track projects it creates
 * @param gitUrl       Git transport URL of the repository, which Technolinator references as VCS of the Dependency-Track projects it creates
 * @param archived     Whether the repository is archived
 * @param listed       The repository as it was listed. It's bound to a client, whose token might be expired when the repository is looked at
 *                     late in a long run, use {@link #github()} for a valid one
 * @param installation Provides clients of the installation granting access to the repository
 */
public record GitHubRepo(
    RepositoryReference reference,
    String name,
    String fullName,
    String htmlUrl,
    String gitUrl,
    boolean archived,
    GHRepository listed,
    GitHubInstallationClient installation
) {

    static GitHubRepo of(String host, String owner, GHRepository repository, GitHubInstallationClient installation) {
        var name = repository.getName();
        var htmlUrl = Objects.toString(repository.getHtmlUrl(), "https://%s/%s/%s".formatted(host, owner, name));
        var gitUrl = Objects.requireNonNullElse(repository.getGitTransportUrl(), "git://%s/%s/%s.git".formatted(host, owner, name));
        return new GitHubRepo(
            new RepositoryReference(host.toLowerCase(Locale.ROOT), owner.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)),
            name,
            owner + "/" + name,
            htmlUrl,
            gitUrl,
            repository.isArchived(),
            repository,
            installation
        );
    }

    /**
     * @return a GitHub client authenticated as the installation, valid for at least 5 more minutes
     */
    public GitHub github() {
        return installation.current();
    }
}
