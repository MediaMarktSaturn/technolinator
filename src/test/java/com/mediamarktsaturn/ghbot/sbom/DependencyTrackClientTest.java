package com.mediamarktsaturn.ghbot.sbom;

import static com.mediamarktsaturn.ghbot.MockServerResource.API_KEY;
import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.MediaType;

import com.mediamarktsaturn.ghbot.DependencyTrackMockServer;
import com.mediamarktsaturn.ghbot.MockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;

@QuarkusTest
@QuarkusTestResource(value = MockServerResource.class, restrictToAnnotatedClass = true)
public class DependencyTrackClientTest {

    @DependencyTrackMockServer
    MockServerClient dtrackMock;

    @Inject
    DependencyTrackClient cut;

    @BeforeEach
    public void setup() {
        dtrackMock.reset();
    }

    @Test
    public void testSuccessfulUpload() {
        // Given
        var putBom = request()
            .withPath("/api/v1/bom")
            .withContentType(MediaType.APPLICATION_JSON)
            .withHeader("X-API-Key", API_KEY)
            .withMethod("PUT");
        dtrackMock.when(putBom).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    {"token":"test-upload"}
                    """)
        );

        var getProjects = request()
            .withPath("/api/v1/project")
            .withQueryStringParameter("name", "test-project")
            .withQueryStringParameter("excludeInactive", "true")
            .withHeader("Accept", "application/json")
            .withHeader("X-API-Key", API_KEY)
            .withMethod("GET");
        dtrackMock.when(getProjects).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    [
                         {
                             "name": "test-project",
                             "version": "1.2.1",
                             "uuid": "uuid-1"
                         },
                         {
                             "name": "test-project",
                             "version": "1.2.2",
                             "uuid": "uuid-2"
                         },
                         {
                             "name": "test-project",
                             "version": "1.2.3",
                             "uuid": "uuid-3"
                         }
                    ]
                     """)
        );

        var patchProject = request()
            .withPath("/api/v1/project/.+")
            .withContentType(MediaType.APPLICATION_JSON)
            .withHeader("X-API-Key", API_KEY)
            .withMethod("PATCH");
        dtrackMock.when(patchProject).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("{}")
        );

        var sbom = JsonObject.of("hell", "yeah", "oh", "wow");
        var name = "test-project";
        var version = "1.2.3";

        // When
        var result = await(cut.uploadSBOM(name, version, sbom));

        // Then
        assertThat(result).isInstanceOf(DependencyTrackClient.UploadResult.Success.class);

        var uploadedValue = dtrackMock.retrieveRecordedRequests(putBom)[0].getBodyAsString();
        var disabledProjects = dtrackMock.retrieveRecordedRequests(patchProject);
        assertThat(disabledProjects).hasSize(2);
        assertThat(disabledProjects[0].getPath()).isEqualTo("/api/v1/project/uuid-1");
        assertThat(disabledProjects[1].getPath()).isEqualTo("/api/v1/project/uuid-2");

        var uploadedJson = new JsonObject(uploadedValue);
        var uploadedName = uploadedJson.getString("projectName");
        var uploadedVersion = uploadedJson.getString("projectVersion");
        var uploadedAutoCreate = uploadedJson.getBoolean("autoCreate");

        assertThat(uploadedName).isEqualTo(name);
        assertThat(uploadedVersion).isEqualTo(version);
        assertThat(uploadedAutoCreate).isTrue();

        var uploadedBom = uploadedJson.getString("bom");
        var clearTextBom = new String(Base64.getDecoder().decode(uploadedBom), StandardCharsets.UTF_8);
        assertThat(clearTextBom).isEqualTo("{\"hell\":\"yeah\",\"oh\":\"wow\"}");
    }

    @Test
    public void testFailingUpload() {
        // Given
        var putBom = request()
            .withPath("/api/v1/bom")
            .withContentType(MediaType.APPLICATION_JSON)
            .withHeader("X-API-Key", API_KEY)
            .withMethod("PUT");
        dtrackMock.when(putBom).respond(
            response()
                .withStatusCode(500)
        );
        var sbom = JsonObject.of();
        var name = "test-project";
        var version = "3.2.1";

        // When
        var result = await(cut.uploadSBOM(name, version, sbom));

        // Then
        assertThat(result).isInstanceOf(DependencyTrackClient.UploadResult.Failure.class);
        assertThat(dtrackMock.retrieveRecordedRequests(putBom)).hasSize(4);
    }
}
