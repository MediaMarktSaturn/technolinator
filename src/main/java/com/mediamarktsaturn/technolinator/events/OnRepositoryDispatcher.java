package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Repository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.util.Optional;

@ApplicationScoped
public class OnRepositoryDispatcher extends DispatcherBase {

    @ConfigProperty(name = "app.archive_projects.enabled")
    boolean archiveProjectsEnabled;

    @ConfigProperty(name = "app.rename_projects.enabled")
    boolean renameProjectsEnabled;

    @SuppressWarnings("unused")
        // called by the quarkus-github-app extension
    void onRepositoryArchived(@Repository.Archived GHEventPayload.Repository payload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> config) {
        var repo = payload.getRepository();
        if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.debugf("Disabled for repo %s by repository config", repo.getHtmlUrl());
        } else {
            archiveProjects(repo, "archived", "repo_archived");
        }
    }

    @SuppressWarnings("unused")
        // called by the quarkus-github-app extension
    void onRepositoryDeleted(@Repository.Deleted GHEventPayload.Repository payload) {
        // the repository is gone, so its technolinator.yml can't be consulted
        archiveProjects(payload.getRepository(), "deleted", "repo_deleted");
    }

    private void archiveProjects(GHRepository repo, String event, String counterName) {
        final var repoUrl = repo.getHtmlUrl();
        final var repoName = repo.getName();

        if (!archiveProjectsEnabled) {
            Log.debugf("Archiving projects is disabled by global config, ignoring %s repo %s", event, repoUrl);
        } else if (!isEnabledByConfig(repoName)) {
            Log.debugf("Repo %s excluded by global config", repoUrl);
        } else {
            final var metadata = new Command.Metadata(repo.getDefaultBranch(), repo.getFullName(), createTraceId(), Optional.empty());
            metadata.writeToMDC();

            Log.infof("Repository %s got %s, archiving related projects", repoUrl, event);
            meterRegistry.counter(counterName, "repo", repoName).increment();

            handler.onRepositoryArchived(repo)
                .ifNoItem().after(analysisTimeout).fail()
                .subscribe().with(
                    /* onItem */
                    count -> {
                        metadata.writeToMDC();
                        Log.infof("Archived %d project(s) of %s repo %s", count, event, repoUrl);
                    },
                    /* onFailure */
                    failure -> {
                        metadata.writeToMDC();
                        Log.warnf(failure, "Failed to archive projects of %s repo %s", event, repoUrl);
                    }
                );
        }
    }

    @SuppressWarnings({"unused", "deprecation"})
        // called by the quarkus-github-app extension
    void onRepositoryRenamed(@Repository.Renamed GHEventPayload.Repository payload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> config) {
        var repo = payload.getRepository();
        final var repoUrl = repo.getHtmlUrl();
        final var repoName = repo.getName();

        if (!renameProjectsEnabled) {
            Log.debugf("Renaming projects is disabled by global config, ignoring renamed repo %s", repoUrl);
        } else if (!isEnabledByConfig(repoName)) {
            Log.debugf("Repo %s excluded by global config", repoUrl);
        } else if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.debugf("Disabled for repo %s by repository config", repoUrl);
        } else {
            final var oldName = Optional.ofNullable(payload.getChanges())
                .map(c -> c.getRepository())
                .map(r -> r.getName())
                .map(n -> n.getFrom())
                .filter(n -> !n.isBlank());

            if (oldName.isEmpty()) {
                Log.warnf("Repository %s got renamed, but the event doesn't contain the previous name, ignoring", repoUrl);
                return;
            }

            final var metadata = new Command.Metadata(repo.getDefaultBranch(), repo.getFullName(), createTraceId(), Optional.empty());
            metadata.writeToMDC();

            Log.infof("Repository %s got renamed from %s, renaming related projects", repoUrl, oldName.get());
            meterRegistry.counter("repo_renamed", "repo", repoName).increment();

            handler.onRepositoryRenamed(repo, oldName.get())
                .ifNoItem().after(analysisTimeout).fail()
                .subscribe().with(
                    /* onItem */
                    result -> {
                        metadata.writeToMDC();
                        Log.infof("Renamed repo %s: %d project(s) renamed, %d archived instead, %d failed",
                            repoUrl, result.renamed(), result.archived(), result.failed());
                    },
                    /* onFailure */
                    failure -> {
                        metadata.writeToMDC();
                        Log.warnf(failure, "Failed to rename projects of renamed repo %s", repoUrl);
                    }
                );
        }
    }
}
