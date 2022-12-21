package com.mediamarktsaturn.ghbot;

import java.util.Map;
import java.util.UUID;

import org.mockserver.client.MockServerClient;
import org.testcontainers.containers.MockServerContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class MockServerResource implements QuarkusTestResourceLifecycleManager {

    public static final String API_KEY = UUID.randomUUID().toString();

    private MockServerContainer mockServer = new MockServerContainer(TestUtil.fromDockerfile("mockserver.Dockerfile"));

    @Override
    public Map<String, String> start() {
        mockServer.start();
        return Map.of(
            "dtrack.url", mockServer.getEndpoint(),
            "dtrack.apikey", API_KEY
        );
    }

    @Override
    public void stop() {
        mockServer.stop();
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(
            new MockServerClient(mockServer.getHost(), mockServer.getServerPort()),
            new TestInjector.AnnotatedAndMatchesType(DependencyTrackMockServer.class,
                MockServerClient.class)
        );
    }
}
