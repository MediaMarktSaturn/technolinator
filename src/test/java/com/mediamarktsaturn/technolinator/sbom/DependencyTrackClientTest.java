package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.DependencyTrackMockServer;
import com.mediamarktsaturn.technolinator.MockServerResource;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static com.mediamarktsaturn.technolinator.MockServerResource.API_KEY;
import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@QuarkusTest
@QuarkusTestResource(value = MockServerResource.class, restrictToAnnotatedClass = true)
class DependencyTrackClientTest {

    @DependencyTrackMockServer
    MockServerClient dtrackMock;

    @Inject
    DependencyTrackClient cut;

    @BeforeEach
    void setup() {
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
                        "uuid": "uuid-3"
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

        var tags = List.of("thisIsGreat", "awesome_project", "42");
        var description = "this is just a great test project";
        var website = "https://github.com/MediaMarktSaturn/awesome-project";
        var vcs = "git://github.com/MediaMarktSaturn/awesome-project.git";
        var projectDetails = new RepositoryDetails(name, version, description, website, vcs, tags);

        // When
        var result = await(cut.uploadSBOM(projectDetails, sbom, name));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(Project.Available.class, s -> assertThat(s.url()).endsWith("/projects/uuid-3"));
        });

        var patchedProjects = dtrackMock.retrieveRecordedRequests(patchProject);
        Arrays.sort(patchedProjects, Comparator.comparing(HttpRequest::getPath).reversed());

        assertThat(patchedProjects).hasSize(3);
        assertThat(patchedProjects[0]).satisfies(disabled -> {
            assertThat(disabled.getPath()).hasToString("/api/v1/project/uuid-1");
            var json = new JsonObject(disabled.getBodyAsString());
            assertThat(json.getBoolean("active")).isFalse();
        });
        assertThat(patchedProjects[1]).satisfies(disabled -> {
            assertThat(disabled.getPath()).hasToString("/api/v1/project/uuid-2");
            var json = new JsonObject(disabled.getBodyAsString());
            assertThat(json.getBoolean("active")).isFalse();
        });
        assertThat(patchedProjects[2]).satisfies(described -> {
            assertThat(described.getPath()).hasToString("/api/v1/project/uuid-3");
            var json = new JsonObject(described.getBodyAsString());
            assertThat(json.getBoolean("active")).isTrue();
            assertThat(json.getString("description")).hasToString(description);
            assertThat(json.getJsonArray("tags")).containsExactlyInAnyOrder(
                JsonObject.of("name", "thisIsGreat"),
                JsonObject.of("name", "awesome_project"),
                JsonObject.of("name", "42")
            );
            assertThat(json.getJsonArray("externalReferences")).containsExactly(
                JsonObject.of(
                    "type", "vcs",
                    "url", vcs
                ),
                JsonObject.of(
                    "type", "website",
                    "url", website
                ),
                JsonObject.of(
                    "type", "release-notes",
                    "url", "https://github.com/MediaMarktSaturn/awesome-project/releases"
                )
            );
        });

        var uploadedValue = dtrackMock.retrieveRecordedRequests(putBom)[0].getBodyAsString();
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

        // When
        var result = await(cut.uploadSBOM(new RepositoryDetails("", "", "", "", "", List.of()), sbom, ""));

        // Then
        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(dtrackMock.retrieveRecordedRequests(putBom)).hasSize(4);
    }
}
