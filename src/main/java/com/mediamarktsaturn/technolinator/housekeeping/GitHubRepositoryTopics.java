package com.mediamarktsaturn.technolinator.housekeeping;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Looks up the topics of repositories, which Technolinator tags the Dependency-Track projects with
 */
@ApplicationScoped
public class GitHubRepositoryTopics {

    /**
     * Blocking, as there's no way to find out the topics without calling GitHub (twice, as the repository listed before is bound to a client
     * possibly expired by now).
     *
     * @return the topics of the repository as set on GitHub
     * @throws IOException if the topics can't be fetched
     */
    public List<String> topics(GitHubRepo repository) throws IOException {
        var topics = repository.github().getRepository(repository.fullName()).listTopics();
        return topics == null ? List.of() : topics.stream().filter(Objects::nonNull).toList();
    }
}
