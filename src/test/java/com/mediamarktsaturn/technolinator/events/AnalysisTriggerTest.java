package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHObject;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTriggerTest {

    private GHApp app;
    private GHAppInstallation installation;
    private GHRepository repository;
    private GitHubConfigFileProvider configFileProvider;
    private OnPushDispatcher dispatcher;
    private AnalysisProcessHandler handler;
    private AnalysisTrigger trigger;

    @BeforeEach
    void setup() throws Exception {
        var clientProvider = Mockito.mock(GitHubClientProvider.class);
        var applicationClient = Mockito.mock(GitHub.class);
        var installationClient = Mockito.mock(GitHub.class);
        app = Mockito.mock(GHApp.class);
        installation = new GHAppInstallation();
        setField(GHObject.class, installation, "id", 42L);
        repository = Mockito.mock(GHRepository.class);
        var branch = Mockito.mock(GHBranch.class);
        when(clientProvider.getApplicationClient()).thenReturn(applicationClient);
        when(applicationClient.getApp()).thenReturn(app);
        when(app.getInstallationByRepository("my-org", "my-repo")).thenReturn(installation);
        when(clientProvider.getInstallationClient(42L)).thenReturn(installationClient);
        when(installationClient.getRepository("my-org/my-repo")).thenReturn(repository);
        when(repository.getFullName()).thenReturn("my-org/my-repo");
        when(repository.getName()).thenReturn("my-repo");
        when(repository.getDefaultBranch()).thenReturn("main");
        when(repository.getBranch("main")).thenReturn(branch);
        when(branch.getSHA1()).thenReturn("c0ffee");

        configFileProvider = Mockito.mock(GitHubConfigFileProvider.class);
        when(configFileProvider.fetchConfigFile(repository, CONFIG_FILE, ConfigFile.Source.DEFAULT, TechnolinatorConfig.class)).thenReturn(Optional.empty());

        dispatcher = Mockito.mock(OnPushDispatcher.class);
        when(dispatcher.createTraceId()).thenReturn("trace-1");
        when(dispatcher.eligibility(any())).thenReturn(DispatcherBase.MetricStatusRepo.ELIGIBLE_FOR_ANALYSIS);
        when(dispatcher.analyze(any(), any())).thenReturn(Uni.createFrom().voidItem());

        handler = Mockito.mock(AnalysisProcessHandler.class);

        trigger = new AnalysisTrigger(clientProvider, configFileProvider, dispatcher, handler);
    }

    @Test
    void analyzesHeadOfDefaultBranchLikeAPush() {
        // Given
        var config = Optional.of(ConfigBuilder.create().project(new TechnolinatorConfig.ProjectConfig("overridden")).build());
        when(configFileProvider.fetchConfigFile(repository, CONFIG_FILE, ConfigFile.Source.DEFAULT, TechnolinatorConfig.class)).thenReturn(config);

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertThat(outcome).isEqualTo(new AnalysisTrigger.Outcome.Started("my-org/my-repo", "main", "c0ffee", "trace-1"));
        var event = ArgumentCaptor.forClass(PushEvent.class);
        var metadata = ArgumentCaptor.forClass(Command.Metadata.class);
        verify(dispatcher).analyze(event.capture(), metadata.capture());
        assertThat(event.getValue()).isEqualTo(new PushEvent(repository, "refs/heads/main", "c0ffee", config));
        assertThat(event.getValue().isDefaultBranch()).isTrue();
        assertThat(OnPushDispatcher.isBranchEligibleForAnalysis(event.getValue())).isTrue();
        assertThat(metadata.getValue()).isEqualTo(new Command.Metadata("refs/heads/main", "my-org/my-repo", "trace-1", Optional.of("c0ffee")));
    }

    @Test
    void allowsOnlyOneAnalysisPerRepositoryAtATime() {
        // Given
        var analysis = new CompletableFuture<Void>();
        when(dispatcher.analyze(any(), any())).thenReturn(Uni.createFrom().completionStage(analysis));

        // When
        var first = trigger.trigger("my-org", "my-repo");
        var second = trigger.trigger("My-Org", "My-Repo");

        // Then
        assertThat(first).isInstanceOf(AnalysisTrigger.Outcome.Started.class);
        assertThat(second).isInstanceOfSatisfying(AnalysisTrigger.Outcome.Rejected.class,
            rejected -> assertThat(rejected.reason()).isEqualTo(AnalysisTrigger.Reason.IN_PROGRESS));

        // When
        analysis.complete(null);

        // Then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(trigger.trigger("my-org", "my-repo")).isInstanceOf(AnalysisTrigger.Outcome.Started.class));
    }

    @Test
    void rejectsWhilePushAnalysisOfSameCommitIsRunning() {
        // Given
        when(handler.isPushAnalysisRunning(new Command.Metadata("refs/heads/main", "my-org/my-repo", "trace-1", Optional.of("c0ffee")))).thenReturn(true);

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.IN_PROGRESS);
    }

    @Test
    void rejectsUnknownRepository() throws Exception {
        // Given
        when(app.getInstallationByRepository("my-org", "my-repo")).thenThrow(new GHFileNotFoundException("not installed"));

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.NOT_FOUND);
        // the repository isn't blocked by the failed attempt
        assertRejected(trigger.trigger("my-org", "my-repo"), AnalysisTrigger.Reason.NOT_FOUND);
    }

    @Test
    void rejectsRepositoryWithoutDefaultBranch() throws Exception {
        // Given
        when(repository.getBranch("main")).thenThrow(new GHFileNotFoundException("no branch"));

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.NOT_FOUND);
    }

    @Test
    void reportsFailingGitHub() throws Exception {
        // Given
        when(app.getInstallationByRepository("my-org", "my-repo")).thenThrow(new IOException("rate limit"));

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.GITHUB_FAILURE);
    }

    @Test
    void rejectsSuspendedInstallation() throws Exception {
        // Given
        setField(GHAppInstallation.class, installation, "suspendedAt", "2026-01-01T00:00:00Z");

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.NOT_ELIGIBLE);
    }

    @Test
    void rejectsArchivedRepository() {
        // Given
        when(repository.isArchived()).thenReturn(true);

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.NOT_ELIGIBLE);
    }

    @Test
    void rejectsDisabledRepository() {
        // Given
        when(dispatcher.eligibility(any())).thenReturn(DispatcherBase.MetricStatusRepo.DISABLED_BY_REPO);

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.NOT_ELIGIBLE);
    }

    @Test
    void rejectsInvalidConfig() {
        // Given
        when(configFileProvider.fetchConfigFile(repository, CONFIG_FILE, ConfigFile.Source.DEFAULT, TechnolinatorConfig.class))
            .thenThrow(new IllegalStateException("broken yaml"));

        // When
        var outcome = trigger.trigger("my-org", "my-repo");

        // Then
        assertRejected(outcome, AnalysisTrigger.Reason.INVALID_CONFIG);
    }

    private void assertRejected(AnalysisTrigger.Outcome outcome, AnalysisTrigger.Reason reason) {
        assertThat(outcome).isInstanceOfSatisfying(AnalysisTrigger.Outcome.Rejected.class,
            rejected -> assertThat(rejected.reason()).isEqualTo(reason));
        verify(dispatcher, never()).analyze(any(), any());
    }

    /**
     * GHObject#getId can't be mocked by Mockito, see GitHubRepositoryIndexerTest, so a real installation with its fields set reflectively is used.
     */
    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
