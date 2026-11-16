package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Repository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHEventPayload;

import java.util.Optional;

@ApplicationScoped
public class OnRepositoryDispatcher extends DispatcherBase {

    @ConfigProperty(name = "app.archive_projects.enabled")
    boolean archiveProjectsEnabled;

    @SuppressWarnings("unused")
        // called by the quarkus-github-app extension
    void onRepositoryArchived(@Repository.Archived GHEventPayload.Repository payload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> config) {
        var repo = payload.getRepository();
        final var repoUrl = repo.getHtmlUrl();
        final var repoName = repo.getName();

        if (!archiveProjectsEnabled) {
            Log.debugf("Archiving projects is disabled by global config, ignoring archived repo %s", repoUrl);
        } else if (!isEnabledByConfig(repoName)) {
            Log.debugf("Repo %s excluded by global config", repoUrl);
        } else if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.debugf("Disabled for repo %s by repository config", repoUrl);
        } else {
            final var metadata = new Command.Metadata(repo.getDefaultBranch(), repo.getFullName(), createTraceId(), Optional.empty());
            metadata.writeToMDC();

            Log.infof("Repository %s got archived, archiving related projects", repoUrl);
            meterRegistry.counter("repo_archived", "repo", repoName).increment();

            handler.onRepositoryArchived(repo)
                .ifNoItem().after(analysisTimeout).fail()
                .subscribe().with(
                    /* onItem */
                    count -> {
                        metadata.writeToMDC();
                        Log.infof("Archived %d project(s) of archived repo %s", count, repoUrl);
                    },
                    /* onFailure */
                    failure -> {
                        metadata.writeToMDC();
                        Log.warnf(failure, "Failed to archive projects of archived repo %s", repoUrl);
                    }
                );
        }
    }
}
