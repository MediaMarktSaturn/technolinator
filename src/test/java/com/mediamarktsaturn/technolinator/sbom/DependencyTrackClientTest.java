package com.mediamarktsaturn.technolinator.sbom;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@ConnectWireMock
class DependencyTrackClientTest {

    @Inject
    DependencyTrackClient cut;

    // Injected because of @ConnectWireMock
    WireMock wiremock;

    private static final String API_KEY = "test-dtrack-api-key";

    @BeforeEach
    void reset() {
        wiremock.resetRequests();
    }

    @Test
    void testSuccessfulUpload() throws ParseException {
        // Given
        var name = "test-project";
        var version = "1.2.3";

        wiremock.register(
            put(urlEqualTo("/api/v1/bom"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"token":"test-upload"}
                            """))
        );

        wiremock.register(
            get(urlMatching("/api/v1/project\\?.*"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("name", equalTo("test-project"))
                .withQueryParam("excludeInactive", equalTo("true"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
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
                )
        );

        wiremock.register(
            patch(urlMatching("/api/v1/project/.+"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")
                )
        );

        wiremock.register(
            get(urlMatching("/api/v1/project/lookup\\?.*"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("name", equalTo(name))
                .withQueryParam("version", equalTo(version))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "name": "test-project",
                              "version": "1.2.3",
                              "uuid": "uuid-3"
                            }
                            """)
                )
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
        var commitSha = "1234567891011121314151617181920";

        // When
        var result = await(cut.uploadSBOM(projectDetails, sbom, name, Project.none(), Optional.of(commitSha)));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(Project.Available.class, s -> assertThat(s.url()).endsWith("/projects/uuid-3"));
        });

        var patchedProjects = wiremock.find(patchRequestedFor(urlMatching(".*")));

        assertThat(patchedProjects).hasSize(3);
        assertThat(patchedProjects.get(0)).satisfies(disabled -> {
            assertThat(disabled.getUrl()).hasToString("/api/v1/project/uuid-1");
            var json = new JsonObject(disabled.getBodyAsString());
            assertThat(json.getBoolean("active")).isFalse();
        });
        assertThat(patchedProjects.get(1)).satisfies(disabled -> {
            assertThat(disabled.getUrl()).hasToString("/api/v1/project/uuid-2");
            var json = new JsonObject(disabled.getBodyAsString());
            assertThat(json.getBoolean("active")).isFalse();
        });
        assertThat(patchedProjects.get(2)).satisfies(described -> {
            assertThat(described.getUrl()).hasToString("/api/v1/project/uuid-3");
            var json = new JsonObject(described.getBodyAsString());
            assertThat(json.getBoolean("active")).isTrue();
            assertThat(json.getString("description")).hasToString("#1234567# " + description);
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

        var putBoms = wiremock.find(putRequestedFor(urlMatching(".*")));
        assertThat(putBoms).hasSize(1);

        var uploadedValue = putBoms.get(0).getBodyAsString();
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
        wiremock.register(
            put(urlEqualTo("/api/v1/bom"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)
                )
        );
        var sbom = new Bom();

        // When
        var result = await(cut.uploadSBOM(new RepositoryDetails("", "", "", "", "", List.of()), sbom, "", Project.none(), Optional.empty()));

        // Then
        assertThat(result).isInstanceOf(Result.Failure.class);

        var putRequests = wiremock.find(putRequestedFor(urlMatching(".*")));
        // client retries 3 times
        assertThat(putRequests).hasSize(4);
    }

    @Test
    void test503StatusFromDTrackServer() {
        // Given
        wiremock.register(
            put(urlEqualTo("/api/v1/bom"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse().withStatus(503)
                )
        );
        var sbom = new Bom();

        // When
        var result = await(cut.uploadSBOM(new RepositoryDetails("", "", "", "", "", List.of()), sbom, "", Project.none(), Optional.empty()));

        // Then
        assertThat(result).isInstanceOf(Result.Failure.class);
        Result.Failure<Project> failure = (Result.Failure<Project>) result;
        assertThat(failure.toString()).contains("Dependency Track Server Http Status 503");

        var putRequests = wiremock.find(putRequestedFor(urlMatching(".*")));
        // client retries 3 times
        assertThat(putRequests).hasSize(4);
    }

    @Test
    void testParentProjectCreation() {
        // Given
        wiremock.register(
            put(urlEqualTo("/api/v1/project"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "name": "my-test-parent-project",
                              "description": "my sample parent project",
                              "version": "test",
                              "uuid": "3a50f759-c69f-48d1-a412-9f01e189e4b2",
                              "active": true
                            }
                            """)
                )
        );

        var repoDetails = new RepositoryDetails("my-test-parent-project", "test", "my sample parent project", "link://to.nowhere", "nope", List.of());
        var commitSha = "4321";

        // When
        var project = await(cut.createOrUpdateParentProject(repoDetails, Optional.of(commitSha)));

        // Then
        assertThat(project).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(Project.Available.class, available -> {
                assertThat(available.projectId()).hasToString("3a50f759-c69f-48d1-a412-9f01e189e4b2");
            });
        });

        var puttedProjects = wiremock.find(putRequestedFor(urlMatching(".*")));

        assertThat(puttedProjects).hasSize(1);
        assertThat(puttedProjects.get(0)).satisfies(put -> {
            assertThat(put.getUrl()).hasToString("/api/v1/project");
            var json = new JsonObject(put.getBodyAsString());
            assertThat(json.getBoolean("active")).isTrue();
            assertThat(json.getString("name")).hasToString("my-test-parent-project");
            assertThat(json.getString("version")).hasToString("test");
            assertThat(json.getString("description")).hasToString("#4321# my sample parent project");
        });
    }

    @Test
    void testParentProjectUpdate() {
        // Given
        wiremock.register(
            put(urlEqualTo("/api/v1/project"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        // yes, that's what dtrack actually returns, even with content-type json
                        .withBody("A project with the specified name already exists.")
                )
        );

        wiremock.register(
            get(urlMatching("/api/v1/project/lookup\\?.*"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("name", equalTo("my-updated-parent-project"))
                .withQueryParam("version", equalTo("test"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "name": "my-updated-parent-project",
                              "version": "test",
                              "uuid": "9a50f759-c69f-48d1-a412-9f01e189e4b2"
                            }
                            """)
                )
        );

        wiremock.register(
            patch(urlEqualTo("/api/v1/project/9a50f759-c69f-48d1-a412-9f01e189e4b2"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")
                )
        );

        var repoDetails = new RepositoryDetails("my-updated-parent-project", "test",
            /*description longer than 255 chars*/ "that's a really long description because we need to test whether our dependency-track client correctly trims really long (but of cause always valuable) description information for a project to a maximum of 255 characters what's the longest value allowed to be submitted to dependency-track, indeed",
            "link://to.nowhere", "nope", List.of());

        // When
        var project = await(cut.createOrUpdateParentProject(repoDetails, Optional.of("myhash")));

        // Then
        assertThat(project).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(Project.Available.class, available -> {
                assertThat(available.projectId()).hasToString("9a50f759-c69f-48d1-a412-9f01e189e4b2");
            });
        });

        var puttedProjects = wiremock.find(putRequestedFor(urlMatching(".*")));
        assertThat(puttedProjects).hasSize(1);
        assertThat(puttedProjects.get(0)).satisfies(put -> {
            assertThat(put.getUrl()).hasToString("/api/v1/project");
            var json = new JsonObject(put.getBodyAsString());
            assertThat(json.getBoolean("active")).isTrue();
            assertThat(json.getString("name")).hasToString("my-updated-parent-project");
            assertThat(json.getString("version")).hasToString("test");
            assertThat(json.getString("description")).hasSizeLessThan(255).startsWith("#myhash# that's a really long desc").endsWith("...");
        });

        var lookedupProjects = wiremock.find(getRequestedFor(urlMatching(".*")));
        assertThat(lookedupProjects).hasSize(1);
        assertThat(lookedupProjects.get(0)).satisfies(put -> {
            assertThat(put.getUrl()).matches("/api/v1/project/lookup\\?.+");
        });

        var patchedProjects = wiremock.find(patchRequestedFor(urlMatching(".*")));
        assertThat(patchedProjects).hasSize(1);
        assertThat(patchedProjects.get(0)).satisfies(patch -> {
            assertThat(patch.getUrl()).hasToString("/api/v1/project/9a50f759-c69f-48d1-a412-9f01e189e4b2");
            var json = new JsonObject(patch.getBodyAsString());
            assertThat(json.getBoolean("active")).isTrue();
            assertThat(json.getString("description")).hasSizeLessThan(255).startsWith("#myhash# that's a really long desc").endsWith("...");
        });
    }

    @Test
    void testAppendVersionInformation() {
        // Given
        wiremock.register(
            get(urlMatching("/api/v1/project/lookup\\?.*"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("name", equalTo("to-be-versioned"))
                .withQueryParam("version", equalTo("test"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                              "name": "to-be-versioned",
                              "version": "test",
                              "uuid": "ea50f759-c69f-48d1-a412-9f01e189e4b2",
                              "description": "#7654321# This doesn't has a version yet"
                            }
                            """)
                )
        );

        wiremock.register(
            patch(urlEqualTo("/api/v1/project/ea50f759-c69f-48d1-a412-9f01e189e4b2"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")
                )
        );

        var repoDetails = new RepositoryDetails("to-be-versioned", "test", "Version me, plenty", "link://to.nowhere", "nope", List.of());

        // When
        var project = await(cut.appendVersionInfo(repoDetails, "v23.5", Optional.of("7654321dklj34toasldfkaj")));

        // Then
        assertThat(project).isInstanceOfSatisfying(Result.Success.class, success -> {
            assertThat(success.result()).isInstanceOfSatisfying(Project.Available.class, available -> {
                assertThat(available.projectId()).hasToString("ea50f759-c69f-48d1-a412-9f01e189e4b2");
            });
        });

        var lookedupProjects = wiremock.find(getRequestedFor(urlMatching(".*")));
        assertThat(lookedupProjects).hasSize(1);
        assertThat(lookedupProjects.get(0)).satisfies(put -> {
            assertThat(put.getUrl()).matches("/api/v1/project/lookup\\?.*");
        });

        var patchedProjects = wiremock.find(patchRequestedFor(urlMatching(".*")));
        assertThat(patchedProjects).hasSize(1);
        assertThat(patchedProjects.get(0)).satisfies(patch -> {
            assertThat(patch.getUrl()).hasToString("/api/v1/project/ea50f759-c69f-48d1-a412-9f01e189e4b2");
            var json = new JsonObject(patch.getBodyAsString());
            assertThat(json.getBoolean("active")).isTrue();
            assertThat(json.getString("description")).hasToString("#7654321# v23.5 | Version me, plenty");
        });
    }

    @Test
    void testParseCommitShaFromDescription() {
        assertThat(cut.parseCommitShaFromDescription("")).isEqualTo(Optional.empty());
        assertThat(cut.parseCommitShaFromDescription(" # that's worthless")).isEqualTo(Optional.empty());
        assertThat(cut.parseCommitShaFromDescription("#pro #dude")).isEqualTo(Optional.empty());
        assertThat(cut.parseCommitShaFromDescription("#thatstolong#")).isEqualTo(Optional.empty());
        assertThat(cut.parseCommitShaFromDescription("#1234567#")).isEqualTo(Optional.of("1234567"));
        assertThat(cut.parseCommitShaFromDescription("#54321#")).isEqualTo(Optional.of("54321"));
        assertThat(cut.parseCommitShaFromDescription("#abcdef0#")).isEqualTo(Optional.of("abcdef0"));
    }
}
