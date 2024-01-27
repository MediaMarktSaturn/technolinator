package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHTag;
import org.kohsuke.github.PagedIterable;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.url;
import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@GitHubAppTest
class OnReleaseDispatcherTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
    }

    @Test
    void testReleaseReleased() throws IOException {
        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                var tag = mock(GHTag.class);
                when(tag.getName()).thenReturn("v0.0.1");
                var commit = mock(GHCommit.class);
                when(commit.getSHA1()).thenReturn("945782495872394572349");
                when(tag.getCommit()).thenReturn(commit);
                var repo = mocks.repository("heubeck/app-test");
                PagedIterable<GHTag> pi = mock(PagedIterable.class);
                when(pi.spliterator()).thenReturn(List.of(tag).spliterator());
                when(repo.listTags()).thenReturn(pi);
            })
            .when()
            .payloadFromClasspath("/events/release_released.json")
            .event(GHEvent.RELEASE);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler)
            .onRelease(argThat(matches("https://github.com/heubeck/app-test", "v0.0.1", "main", null)), any()));
    }

    @Test
    void testReleasePublished() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/release_published.json")
            .event(GHEvent.RELEASE);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    @Test
    void testReleaseOnDisabledRepo() throws IOException {
        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/disabled.yml"))
            .when()
            .payloadFromClasspath("/events/release_released.json")
            .event(GHEvent.RELEASE);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    static ArgumentMatcher<ReleaseEvent> matches(String repoUrl, String pushRef, String defaultBranch, TechnolinatorConfig config) {
        return got ->
            got.repository().getHtmlUrl().sameFile(url(repoUrl))
                && got.ref().equals(pushRef)
                && got.defaultBranch().equals(defaultBranch)
                && got.config().equals(Optional.ofNullable(config));
    }
}
