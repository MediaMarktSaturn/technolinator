package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;

/**
 * Reads the Technolinator configuration ({@code .github/technolinator.yml}) of repositories the way the analysis of the default branch does
 */
@ApplicationScoped
public class GitHubRepositoryConfig {

    private final GitHubConfigFileProvider configFileProvider;

    @Inject
    public GitHubRepositoryConfig(GitHubConfigFileProvider configFileProvider) {
        this.configFileProvider = configFileProvider;
    }

    /**
     * Blocking. The repository listed before is bound to a client possibly expired by now, so it's looked up again.
     *
     * @return the configuration of the default branch, empty if the repository doesn't have one
     * @throws IOException      if the repository can't be looked up
     * @throws RuntimeException if the configuration file exists, but can't be read or parsed
     */
    public Optional<TechnolinatorConfig> config(GitHubRepo repository) throws IOException {
        var current = repository.github().getRepository(repository.fullName());
        return configFileProvider.fetchConfigFile(current, CONFIG_FILE, ConfigFile.Source.DEFAULT, TechnolinatorConfig.class);
    }
}
