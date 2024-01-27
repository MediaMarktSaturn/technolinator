package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Actions;
import io.quarkiverse.githubapp.event.Release;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class OnReleaseDispatcher extends DispatcherBase {

    @SuppressWarnings("unused")
        // called by the quarkus-github-app extension
    void onPush(@Release GHEventPayload.Release releasePayload, @ConfigFile(CONFIG_FILE) Optional<TechnolinatorConfig> config) {
        if (!Actions.RELEASED.equals(releasePayload.getAction())) {
            // we're only interested in the actual release of a release
            return;
        }

        var repo = releasePayload.getRepository();
        final var repoUrl = repo.getHtmlUrl();
        final var repoName = repo.getName();

        if (!isEnabledByConfig(repoName)) {
            Log.debugf("Repo %s excluded by global config", repoUrl);
        } else if (!config.map(TechnolinatorConfig::enable).orElse(true)) {
            Log.debugf("Disabled for repo %s by repository config", repoUrl);
        } else {
            var traceId = createTraceId();
            var release = releasePayload.getRelease();
            var releaseTag = release.getTagName();
            var commitSha = getTaggedCommit(releaseTag, repo);

            if (commitSha.isEmpty()) {
                return;
            }

            Log.infof("Repository %s released '%s' from tag '%s' targeting commit '%s'",
                releasePayload.getRepository().getUrl(),
                release.getName(),
                release.getTagName(),
                commitSha.get()
            );

            final var metadata = new Command.Metadata(releaseTag, repo.getFullName(), traceId, commitSha);
            metadata.writeToMDC();

            handler.onRelease(new ReleaseEvent(releasePayload, config), metadata)
                .ifNoItem().after(analysisTimeout).fail()
                .subscribe().with(
                    /* onItem */
                    item -> {
                        metadata.writeToMDC();
                        if (item instanceof Result.Success<Project> s && s.result() instanceof Project.Available a) {
                            Log.infof("Release tag %s of repo %s added to project %s", releaseTag, repoUrl, a.projectId());
                        } else {
                            Log.warnf("Release tag %s of repo %s wasn't handled, result: %s", releaseTag, repoUrl, item);
                        }
                    },
                    /* onFailure */
                    failure -> {
                        metadata.writeToMDC();
                        Log.warnf(failure, "Failed to handle release tag %s of repo %s", releaseTag, repoUrl);
                    }
                );
        }
    }

    private Optional<String> getTaggedCommit(String tagName, GHRepository repo) {
        try {
            return StreamSupport.stream(repo.listTags().spliterator(), false)
                .filter(t -> t.getName().equals(tagName)).findFirst()
                .map(t -> t.getCommit().getSHA1());
        } catch (IOException e) {
            Log.errorf(e, "Failed to fetch commit referenced by tag %s in repo %s", tagName, repo.getUrl());
            return Optional.empty();
        }
    }
}
