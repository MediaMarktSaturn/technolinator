package com.mediamarktsaturn.ghbot.sbom;

import static com.mediamarktsaturn.ghbot.MockServerResource.API_KEY;
import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.inject.Inject;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.MediaType;

import com.mediamarktsaturn.ghbot.DependencyTrackMockServer;
import com.mediamarktsaturn.ghbot.MockServerResource;
import com.mediamarktsaturn.ghbot.Result;
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
    void testSuccessfulUpload() throws ParseException {
        // Given
        var name = "test-project";
        var version = "1.2.3";

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

        var lookupProject = request()
            .withPath("/api/v1/project/lookup")
            .withQueryStringParameter("name", name)
            .withQueryStringParameter("version", version)
            .withHeader("Accept", "application/json")
            .withHeader("X-API-Key", API_KEY)
            .withMethod("GET");
        dtrackMock.when(lookupProject).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""
                    {
                        "name": "test-project",
                        "version": "1.2.3",
                        "uuid": "uuid-1"
                    }
                    """)
        );

        var sbom = new Bom();
        var metadata = new Metadata();
        var component = new Component();
        component.setName(name);
        component.setVersion(version);
        metadata.setComponent(component);
        sbom.setMetadata(metadata);

        // When
        var result = await(cut.uploadSBOM(name, version, sbom));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(String.class, s -> assertThat(s).endsWith("/projects/uuid-1"));
        });

        var uploadedValue = dtrackMock.retrieveRecordedRequests(putBom)[0].getBodyAsString();
        var disabledProjects = dtrackMock.retrieveRecordedRequests(patchProject);
        assertThat(disabledProjects).hasSize(2);
        assertThat(disabledProjects[0].getPath()).hasToString("/api/v1/project/uuid-1");
        assertThat(disabledProjects[1].getPath()).hasToString("/api/v1/project/uuid-2");

        var uploadedJson = new JsonObject(uploadedValue);
        var uploadedName = uploadedJson.getString("projectName");
        var uploadedVersion = uploadedJson.getString("projectVersion");
        var uploadedAutoCreate = uploadedJson.getBoolean("autoCreate");

        assertThat(uploadedName).isEqualTo(name);
        assertThat(uploadedVersion).isEqualTo(version);
        assertThat(uploadedAutoCreate).isTrue();

        var uploadedBom = uploadedJson.getString("bom");
        var clearTextBom = new String(Base64.getDecoder().decode(uploadedBom), StandardCharsets.UTF_8);
        var bom = new JsonParser().parse(clearTextBom.getBytes(StandardCharsets.UTF_8));
        assertThat(bom.getMetadata().getComponent().getName()).isEqualTo(name);
        assertThat(bom.getMetadata().getComponent().getVersion()).isEqualTo(version);
    }

    @Test
    void testFailingUpload() {
        // Given
        var putBom = request()
            .withPath("/api/v1/bom")
            .withContentType(MediaType.APPLICATION_JSON)
            .withHeader("X-API-Key", API_KEY)
            .withMethod("PUT");
        dtrackMock.when(putBom).error(
            error().withDropConnection(true)
        );
        var sbom = new Bom();
        var name = "test-project";
        var version = "3.2.1";

        // When
        var result = await(cut.uploadSBOM(name, version, sbom));

        // Then
        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(dtrackMock.retrieveRecordedRequests(putBom)).hasSize(4);
    }
}
