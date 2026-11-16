package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.CustomTestProfiles;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.mockito.Mockito;

import java.io.IOException;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@GitHubAppTest
@TestProfile(CustomTestProfiles.ArchiveProjectsDisabled.class)
class OnRepositoryDispatcherDisabledTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
        when(handler.onRepositoryArchived(any())).thenReturn(Uni.createFrom().item(1));
    }

    @Test
    void testRepositoryArchivedWithArchivingDisabled() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/repository_archived.json")
            .event(GHEvent.REPOSITORY);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    @Test
    void testRepositoryDeletedWithArchivingDisabled() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/repository_deleted.json")
            .event(GHEvent.REPOSITORY);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }
}
