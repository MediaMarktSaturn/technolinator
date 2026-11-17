package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;

/**
 * Triggers the analysis of the default branch of a repository on demand, see {@link AnalysisEndpoint}.
 * <p>
 * The analysis is the very same as for a push to the default branch, see {@link OnPushDispatcher}: the repository is looked up
 * with the installation of the GitHub app having access to it, its configuration is read from the default branch, and the
 * head commit of the default branch is analyzed, including commit status, status issue, pull-request reports and metrics.
 * <p>
 * Only one analysis per repository can be triggered at a time, and none while a push analysis of the same commit is in progress.
 */
@ApplicationScoped
public class AnalysisTrigger {

    /**
     * What happened to a request to analyze a repository
     */
    public sealed interface Outcome {

        /**
         * The analysis started in the background
         */
        record Started(String repository, String branch, String commitSha, String traceId) implements Outcome {
        }

        /**
         * The analysis didn't start
         */
        record Rejected(Reason reason, String message) implements Outcome {
        }
    }

    public enum Reason {
        /**
         * The repository, its default branch or an installation of the GitHub app having access to it don't exist
         */
        NOT_FOUND,
        /**
         * The repository must not be analyzed, e.g. because it's disabled or archived
         */
        NOT_ELIGIBLE,
        /**
         * An analysis of the repository is in progress already
         */
        IN_PROGRESS,
        /**
         * The configuration file of the repository can't be read
         */
        INVALID_CONFIG,
        /**
         * GitHub couldn't be asked about the repository
         */
        GITHUB_FAILURE
    }

    private final GitHubClientProvider clientProvider;
    private final GitHubConfigFileProvider configFileProvider;
    private final OnPushDispatcher dispatcher;
    private final AnalysisProcessHandler handler;

    // repositories (owner/name in lower case) with an analysis triggered on demand in progress
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    public AnalysisTrigger(
        GitHubClientProvider clientProvider,
        GitHubConfigFileProvider configFileProvider,
        OnPushDispatcher dispatcher,
        AnalysisProcessHandler handler
    ) {
        this.clientProvider = clientProvider;
        this.configFileProvider = configFileProvider;
        this.dispatcher = dispatcher;
        this.handler = handler;
    }

    /**
     * Looks up the repository [owner]/[name] and starts the analysis of its default branch in the background.
     * Performs blocking calls to GitHub, so it must not be called on an event loop thread.
     */
    public Outcome trigger(String owner, String name) {
        var key = (owner + "/" + name).toLowerCase(Locale.ROOT);
        if (!inProgress.add(key)) {
            return new Outcome.Rejected(Reason.IN_PROGRESS, "An analysis of repository %s/%s is already in progress".formatted(owner, name));
        }
        var started = false;
        try {
            var outcome = lookupAndStart(owner, name, () -> inProgress.remove(key));
            started = outcome instanceof Outcome.Started;
            return outcome;
        } finally {
            if (!started) {
                inProgress.remove(key);
            }
        }
    }

    private Outcome lookupAndStart(String owner, String name, Runnable onTermination) {
        final GHRepository repository;
        final String commitSha;
        try {
            var installation = clientProvider.getApplicationClient().getApp().getInstallationByRepository(owner, name);
            if (installation.getSuspendedAt() != null) {
                return new Outcome.Rejected(Reason.NOT_ELIGIBLE, "The installation of the GitHub app for repository %s/%s is suspended".formatted(owner, name));
            }
            repository = clientProvider.getInstallationClient(installation.getId()).getRepository(owner + "/" + name);
        } catch (GHFileNotFoundException e) {
            return new Outcome.Rejected(Reason.NOT_FOUND, "Repository %s/%s not found or the GitHub app isn't installed for it".formatted(owner, name));
        } catch (IOException | RuntimeException e) {
            Log.errorf(e, "Could not look up repository %s/%s", owner, name);
            return new Outcome.Rejected(Reason.GITHUB_FAILURE, "Could not look up repository %s/%s".formatted(owner, name));
        }

        var fullName = repository.getFullName();
        if (repository.isArchived()) {
            return new Outcome.Rejected(Reason.NOT_ELIGIBLE, "Repository %s is archived".formatted(fullName));
        }
        var defaultBranch = repository.getDefaultBranch();
        try {
            commitSha = repository.getBranch(defaultBranch).getSHA1();
        } catch (GHFileNotFoundException e) {
            return new Outcome.Rejected(Reason.NOT_FOUND, "Default branch %s of repository %s not found".formatted(defaultBranch, fullName));
        } catch (IOException | RuntimeException e) {
            Log.errorf(e, "Could not look up default branch %s of repository %s", defaultBranch, fullName);
            return new Outcome.Rejected(Reason.GITHUB_FAILURE, "Could not look up default branch %s of repository %s".formatted(defaultBranch, fullName));
        }

        final Optional<TechnolinatorConfig> config;
        try {
            // the same as the GitHub app extension provides to the webhook handlers by `@ConfigFile`
            config = configFileProvider.fetchConfigFile(repository, CONFIG_FILE, ConfigFile.Source.DEFAULT, TechnolinatorConfig.class);
        } catch (RuntimeException e) {
            Log.warnf(e, "Could not read the configuration file of repository %s", fullName);
            return new Outcome.Rejected(Reason.INVALID_CONFIG, "Could not read the configuration file .github/%s of repository %s".formatted(CONFIG_FILE, fullName));
        }

        var event = new PushEvent(repository, PushEvent.BRANCH_REF_PREFIX + defaultBranch, commitSha, config);
        var metadata = new Command.Metadata(event.ref(), fullName, dispatcher.createTraceId(), Optional.of(commitSha));
        metadata.writeToMDC();
        Log.infof("Analysis of ref %s of repository %s triggered on demand", event.ref(), fullName);

        var status = dispatcher.eligibility(event);
        if (status != DispatcherBase.MetricStatusRepo.ELIGIBLE_FOR_ANALYSIS) {
            return new Outcome.Rejected(Reason.NOT_ELIGIBLE, switch (status) {
                case DISABLED_BY_CONFIG -> "Repository %s is excluded by the global configuration".formatted(fullName);
                case DISABLED_BY_REPO -> "Repository %s is disabled by its configuration file".formatted(fullName);
                default -> "Repository %s is not eligible for analysis: %s".formatted(fullName, status);
            });
        }
        if (handler.isPushAnalysisRunning(metadata)) {
            return new Outcome.Rejected(Reason.IN_PROGRESS, "An analysis of commit %s of repository %s is already in progress".formatted(commitSha, fullName));
        }

        dispatcher.analyze(event, metadata)
            // the analysis starts with blocking calls to GitHub, which shouldn't delay the caller
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onTermination().invoke(onTermination)
            .subscribe().with(
                ignored -> {
                },
                // analyze doesn't fail, just a safety net
                failure -> Log.errorf(failure, "Analysis of repository %s failed", fullName)
            );
        return new Outcome.Started(fullName, defaultBranch, commitSha, metadata.traceId());
    }
}
