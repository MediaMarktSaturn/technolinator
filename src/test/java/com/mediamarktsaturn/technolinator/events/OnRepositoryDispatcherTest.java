package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.Mockito;

import java.io.IOException;

import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@GitHubAppTest
class OnRepositoryDispatcherTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
        when(handler.onRepositoryArchived(any())).thenReturn(Uni.createFrom().item(1));
    }

    @Test
    void testRepositoryArchived() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/repository_archived.json")
            .event(GHEvent.REPOSITORY);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler)
            .onRepositoryArchived(Mockito.argThat(repo -> "app-test".equals(repo.getName()))));
    }

    @Test
    void testRepositoryEdited() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/repository_edited.json")
            .event(GHEvent.REPOSITORY);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    @Test
    void testRepositoryArchivedOnDisabledRepo() throws IOException {
        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/disabled.yml"))
            .when()
            .payloadFromClasspath("/events/repository_archived.json")
            .event(GHEvent.REPOSITORY);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }
}
