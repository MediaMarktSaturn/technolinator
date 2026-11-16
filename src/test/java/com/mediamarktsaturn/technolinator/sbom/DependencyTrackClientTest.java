package com.mediamarktsaturn.technolinator.sbom;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.RepositoryDetails;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            post(urlEqualTo("/api/v1/bom"))
                .withHeader("Content-Type", matching("multipart/form-data.*"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withMultipartRequestBody(aMultipart("projectName").withHeader("Content-Type", containing("text/plain")).withBody(equalTo(name)))
                .withMultipartRequestBody(aMultipart("projectVersion").withHeader("Content-Type", containing("text/plain")).withBody(equalTo(version)))
                .withMultipartRequestBody(aMultipart("autoCreate").withHeader("Content-Type", containing("text/plain")).withBody(equalTo("true")))
                .withMultipartRequestBody(aMultipart("isLatest").withHeader("Content-Type", containing("text/plain")).withBody(equalTo("true")))
                .withMultipartRequestBody(aMultipart("bom").withHeader("Content-Type", containing("text/plain")).withBody(and(
                    containing("test-component-name"), containing("test-component-version")
                )))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"token":"test-upload","projectUuid":"uuid-3"}
                            """))
        );

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project/concise"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("name", equalTo("test-project"))
                .withQueryParam("active", equalTo("true"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        // must not break anything, just gets logged
                        .withHeader("X-API-Deprecated", "true")
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
            get(urlEqualTo("/api/v1/event/token/test-upload"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"processing\":false}"))
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

        var sbom = new Bom();
        var metadata = new Metadata();
        var component = new Component();
        component.setName("test-component-name");
        component.setVersion("test-component-version");
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

        var postBoms = wiremock.find(postRequestedFor(urlMatching(".*")));
        assertThat(postBoms).hasSize(1);

        // project uuid is taken from the upload response, no lookup needed
        var getRequests = wiremock.find(getRequestedFor(urlMatching(".*")));
        assertThat(getRequests).hasSize(2);
        assertThat(getRequests.stream().map(LoggedRequest::getUrl)).anySatisfy(url -> assertThat(url).isEqualTo("/api/v1/event/token/test-upload"))
            .anySatisfy(url -> assertThat(url).startsWith("/api/v1/project/concise?"));
    }

    @Test
    void testMetadataIsUpdatedAfterBomProcessingFinished() {
        // Given
        var name = "processing-project";
        var version = "3.0.0";
        var token = "processing-token";
        var scenario = "bom-processing";

        wiremock.register(
            post(urlEqualTo("/api/v1/bom"))
                .withMultipartRequestBody(aMultipart("projectName").withBody(equalTo(name)))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(JsonObject.of("token", token, "projectUuid", "processing-uuid").encode()))
        );
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project/concise"))
                .withQueryParam("name", equalTo(name))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]"))
        );
        wiremock.register(
            get(urlEqualTo("/api/v1/event/token/" + token))
                .inScenario(scenario).whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"processing\":true}"))
                .willSetStateTo("still-processing")
        );
        wiremock.register(
            get(urlEqualTo("/api/v1/event/token/" + token))
                .inScenario(scenario).whenScenarioStateIs("still-processing")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"processing\":true}"))
                .willSetStateTo("done")
        );
        wiremock.register(
            get(urlEqualTo("/api/v1/event/token/" + token))
                .inScenario(scenario).whenScenarioStateIs("done")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"processing\":false}"))
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/processing-uuid"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        var projectDetails = new RepositoryDetails(name, version, "processing", "https://github.com/x/processing", "git://github.com/x/processing.git", List.of("topic"));

        // When
        var result = await(cut.uploadSBOM(projectDetails, new Bom(), name, Project.none(), Optional.empty()));

        // Then
        assertThat(result).isInstanceOf(Result.Success.class);
        var polls = wiremock.find(getRequestedFor(urlEqualTo("/api/v1/event/token/" + token)));
        var patches = wiremock.find(patchRequestedFor(urlEqualTo("/api/v1/project/processing-uuid")));
        assertThat(polls).hasSize(3);
        assertThat(patches).hasSize(1);
        // the first two polls report 'processing', the metadata update must not happen before the last one reports 'done'
        var lastPoll = polls.stream().map(LoggedRequest::getLoggedDate).max(java.util.Date::compareTo).orElseThrow();
        assertThat(patches.get(0).getLoggedDate()).isAfterOrEqualTo(lastPoll);
    }

    @Test
    void testMetadataUpdateIsRetriedOnUnsuccessfulResponse() {
        // Given
        var name = "retry-project";
        var version = "4.0.0";
        var scenario = "patch-retry";

        wiremock.register(
            post(urlEqualTo("/api/v1/bom"))
                .withMultipartRequestBody(aMultipart("projectName").withBody(equalTo(name)))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(JsonObject.of("token", "retry-token", "projectUuid", "retry-uuid").encode()))
        );
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project/concise"))
                .withQueryParam("name", equalTo(name))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]"))
        );
        wiremock.register(
            get(urlEqualTo("/api/v1/event/token/retry-token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"processing\":false}"))
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/retry-uuid"))
                .inScenario(scenario).whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(409).withBody("conflict"))
                .willSetStateTo("conflicted")
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/retry-uuid"))
                .inScenario(scenario).whenScenarioStateIs("conflicted")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        var projectDetails = new RepositoryDetails(name, version, "retry", "https://github.com/x/retry", "git://github.com/x/retry.git", List.of());

        // When
        var result = await(cut.uploadSBOM(projectDetails, new Bom(), name, Project.none(), Optional.empty()));

        // Then
        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(wiremock.find(patchRequestedFor(urlEqualTo("/api/v1/project/retry-uuid")))).hasSize(2);
    }

    @Test
    void testUploadFallsBackToLookupWithoutProjectUuidInResponse() {
        // Given
        var name = "fallback-project";
        var version = "2.0.0";

        wiremock.register(
            post(urlEqualTo("/api/v1/bom"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"token\":\"test-upload\"}"))
        );
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project/concise"))
                .withQueryParam("name", equalTo(name))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]"))
        );
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project/lookup"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("name", equalTo(name))
                .withQueryParam("version", equalTo(version))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(JsonObject.of("name", name, "version", version, "uuid", "fallback-uuid").encode())
                )
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/fallback-uuid"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        var projectDetails = new RepositoryDetails(name, version, "fallback", "https://github.com/x/y", "git://github.com/x/y.git", List.of());

        // When
        var result = await(cut.uploadSBOM(projectDetails, new Bom(), name, Project.none(), Optional.empty()));

        // Then
        assertThat(result).isInstanceOfSatisfying(Result.Success.class, success ->
            assertThat(success.result()).isInstanceOfSatisfying(Project.Available.class, p -> assertThat(p.url()).endsWith("/projects/fallback-uuid")));
        assertThat(wiremock.find(getRequestedFor(urlPathEqualTo("/api/v1/project/lookup")))).hasSize(1);
        assertThat(wiremock.find(patchRequestedFor(urlEqualTo("/api/v1/project/fallback-uuid")))).hasSize(1);
    }

    @Test
    void testFailingUpload() {
        // Given
        wiremock.register(
            post(urlEqualTo("/api/v1/bom"))
                .withHeader("Content-Type", matching("multipart/form-data.*"))
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

        var putRequests = wiremock.find(postRequestedFor(urlMatching(".*")));
        // client retries 3 times
        assertThat(putRequests).hasSize(4);
    }

    @Test
    void test503StatusFromDTrackServer() {
        // Given
        wiremock.register(
            post(urlEqualTo("/api/v1/bom"))
                .withHeader("Content-Type", matching("multipart/form-data.*"))
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
        assertThat(failure.cause()).isInstanceOf(DependencyTrackClientHttpException.class);
        DependencyTrackClientHttpException httpException = (DependencyTrackClientHttpException) failure.cause();
        assertThat(httpException.getHttpStatus()).isEqualTo(503);

        var putRequests = wiremock.find(postRequestedFor(urlMatching(".*")));
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
    void testArchiveProjectsOfRepository() {
        // Given
        var repo = "archive-test";
        var vcs = "git://github.com/heubeck/archive-test.git";
        var website = "https://github.com/heubeck/archive-test";
        var otherVcs = "git://github.com/someone-else/archive-test-other.git";

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("searchText", equalTo(repo))
                .withQueryParam("excludeInactive", equalTo("true"))
                .withQueryParam("pageSize", equalTo("100"))
                .withQueryParam("pageNumber", equalTo("1"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Total-Count", "4")
                        .withBody(JsonArray.of(
                            archiveProject("archive-uuid-parent", repo, vcs, website, null),
                            archiveProject("archive-uuid-child", repo + "-module", vcs, website, "archive-uuid-parent"),
                            archiveProject("archive-uuid-other", repo + "-other", otherVcs, "https://github.com/someone-else/archive-test-other", null),
                            archiveProject("archive-uuid-similar", repo + "2", vcs, website, null)
                        ).encode())
                )
        );

        wiremock.register(
            patch(urlMatching("/api/v1/project/archive-uuid-.+"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        // When
        var archived = await(cut.archiveProjectsOfRepository(repo, vcs, website));

        // Then
        assertThat(archived).isEqualTo(2);

        var patchedProjects = wiremock.find(patchRequestedFor(urlMatching(".*")));
        assertThat(patchedProjects).hasSize(2);
        // children have to be archived before their parents
        assertThat(patchedProjects.stream().map(LoggedRequest::getUrl)).containsExactlyInAnyOrder(
            "/api/v1/project/archive-uuid-child", "/api/v1/project/archive-uuid-parent");
        var child = patchedProjects.stream().filter(r -> r.getUrl().endsWith("archive-uuid-child")).findFirst().orElseThrow();
        var parent = patchedProjects.stream().filter(r -> r.getUrl().endsWith("archive-uuid-parent")).findFirst().orElseThrow();
        assertThat(child.getLoggedDate()).isBeforeOrEqualTo(parent.getLoggedDate());
        patchedProjects.forEach(p -> assertThat(new JsonObject(p.getBodyAsString())).isEqualTo(JsonObject.of("active", false)));
    }

    @Test
    void testArchiveProjectsOfRepositoryWithPaging() {
        // Given
        var repo = "paging-test";
        var vcs = "git://github.com/heubeck/paging-test.git";
        var website = "https://github.com/heubeck/paging-test";

        var firstPage = new JsonArray();
        for (int i = 0; i < 100; i++) {
            firstPage.add(archiveProject("paging-uuid-" + i, repo + "-module" + i, vcs, website, "paging-uuid-parent"));
        }
        var secondPage = JsonArray.of(archiveProject("paging-uuid-parent", repo, vcs, website, null));

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", equalTo(repo))
                .withQueryParam("pageNumber", equalTo("1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withHeader("X-Total-Count", "101").withBody(firstPage.encode()))
        );
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", equalTo(repo))
                .withQueryParam("pageNumber", equalTo("2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withHeader("X-Total-Count", "101").withBody(secondPage.encode()))
        );
        wiremock.register(
            patch(urlMatching("/api/v1/project/paging-uuid-.+"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        // When
        var archived = await(cut.archiveProjectsOfRepository(repo, vcs, website));

        // Then
        assertThat(archived).isEqualTo(101);
        assertThat(wiremock.find(patchRequestedFor(urlMatching(".*")))).hasSize(101);
    }

    @Test
    void testArchiveProjectsOfRepositoryCountsOnlySuccessfulPatches() {
        // Given
        var repo = "partial-test";
        var vcs = "git://github.com/heubeck/partial-test.git";
        var website = "https://github.com/heubeck/partial-test";

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", equalTo(repo))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(JsonArray.of(
                        archiveProject("partial-uuid-ok", repo, vcs, website, null),
                        archiveProject("partial-uuid-conflict", repo + "-module", vcs, website, "partial-uuid-ok")
                    ).encode()))
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/partial-uuid-ok"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/partial-uuid-conflict"))
                .willReturn(aResponse().withStatus(409).withBody("conflict"))
        );

        // When
        var archived = await(cut.archiveProjectsOfRepository(repo, vcs, website));

        // Then
        assertThat(archived).isEqualTo(1);
    }

    @Test
    void testArchiveProjectsOfRepositoryWithFailingList() {
        // Given
        var repo = "failing-list-test";
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", equalTo(repo))
                .willReturn(aResponse().withStatus(500).withBody("boom"))
        );

        // When
        var subscriber = cut.archiveProjectsOfRepository(repo, "git://x", "https://x")
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure().assertFailedWith(DependencyTrackClientHttpException.class);
        assertThat(wiremock.find(patchRequestedFor(urlMatching(".*")))).isEmpty();
    }

    @Test
    void testArchiveProjectsOfRepositoryWithoutExternalReferences() {
        // Given
        var repo = "bare-test";
        var vcs = "git://github.com/heubeck/bare-test.git";
        var website = "https://github.com/heubeck/bare-test";
        var otherVcs = "git://github.com/someone-else/bare-test.git";

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", equalTo(repo))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(JsonArray.of(
                        // no external references at all, matched by exact name
                        archiveProject("bare-uuid-plain", repo, null, null, null).put("externalReferences", null),
                        // empty external references, child of a matched project
                        archiveProject("bare-uuid-child", repo + "-module", null, null, "bare-uuid-plain").put("externalReferences", JsonArray.of()),
                        // nested child without references
                        archiveProject("bare-uuid-grandchild", repo + "-module-sub", null, null, "bare-uuid-child").put("externalReferences", JsonArray.of()),
                        // no references, but no relation to matched projects
                        archiveProject("bare-uuid-orphan", repo + "-orphan", null, null, null).put("externalReferences", JsonArray.of()),
                        // other repository with the same name
                        archiveProject("bare-uuid-foreign", repo, otherVcs, "https://github.com/someone-else/bare-test", null),
                        // child of the foreign project without own references
                        archiveProject("bare-uuid-foreign-child", repo + "-module", null, null, "bare-uuid-foreign").put("externalReferences", JsonArray.of())
                    ).encode()))
        );
        wiremock.register(
            patch(urlMatching("/api/v1/project/bare-uuid-.+"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        // When
        var archived = await(cut.archiveProjectsOfRepository(repo, vcs, website));

        // Then
        // the foreign project and its child (only distinguishable via the parent) as well as the orphan must not be archived
        assertThat(archived).isEqualTo(3);
        var patched = wiremock.find(patchRequestedFor(urlMatching(".*"))).stream().map(LoggedRequest::getUrl).toList();
        assertThat(patched).containsExactlyInAnyOrder(
            "/api/v1/project/bare-uuid-plain", "/api/v1/project/bare-uuid-child", "/api/v1/project/bare-uuid-grandchild");
    }

    @Test
    void testRenameProjectsOfRepository() {
        // Given
        var oldName = "rename-old";
        var newName = "rename-new";
        var oldVcs = "git://github.com/heubeck/rename-old.git";
        var oldWebsite = "https://github.com/heubeck/rename-old";
        var newVcs = "git://github.com/heubeck/rename-new.git";
        var newWebsite = "https://github.com/heubeck/rename-new";

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("searchText", equalTo(oldName))
                .withQueryParam("excludeInactive", equalTo("false"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withHeader("X-Total-Count", "4")
                    .withBody(JsonArray.of(
                        archiveProject("rename-uuid-parent", oldName, oldVcs, oldWebsite, null),
                        archiveProject("rename-uuid-child", oldName + "-module", oldVcs, oldWebsite, "rename-uuid-parent"),
                        // no references at all, matched by exact name
                        archiveProject("rename-uuid-bare", oldName, null, null, null).put("externalReferences", null),
                        // other repository
                        archiveProject("rename-uuid-foreign", oldName, "git://github.com/someone-else/rename-old.git", "https://github.com/someone-else/rename-old", null)
                    ).encode()))
        );
        wiremock.register(
            patch(urlMatching("/api/v1/project/rename-uuid-.+"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        // When
        var result = await(cut.renameProjectsOfRepository(oldName, newName, oldVcs, oldWebsite, newVcs, newWebsite));

        // Then
        assertThat(result).isEqualTo(new DependencyTrackClient.RenameResult(3, 0, 0));

        var patched = wiremock.find(patchRequestedFor(urlMatching(".*")));
        assertThat(patched.stream().map(LoggedRequest::getUrl)).containsExactlyInAnyOrder(
            "/api/v1/project/rename-uuid-parent", "/api/v1/project/rename-uuid-child", "/api/v1/project/rename-uuid-bare");

        var parentBody = new JsonObject(patched.stream().filter(r -> r.getUrl().endsWith("rename-uuid-parent")).findFirst().orElseThrow().getBodyAsString());
        assertThat(parentBody.getString("name")).isEqualTo(newName);
        assertThat(parentBody.getJsonArray("externalReferences")).isEqualTo(JsonArray.of(
            JsonObject.of("type", "vcs", "url", newVcs),
            JsonObject.of("type", "website", "url", newWebsite),
            JsonObject.of("type", "release-notes", "url", newWebsite + "/releases")
        ));

        var childBody = new JsonObject(patched.stream().filter(r -> r.getUrl().endsWith("rename-uuid-child")).findFirst().orElseThrow().getBodyAsString());
        assertThat(childBody.getString("name")).isEqualTo(newName + "-module");
        assertThat(childBody.getJsonArray("externalReferences").getJsonObject(0).getString("url")).isEqualTo(newVcs);

        var bareBody = new JsonObject(patched.stream().filter(r -> r.getUrl().endsWith("rename-uuid-bare")).findFirst().orElseThrow().getBodyAsString());
        assertThat(bareBody).isEqualTo(JsonObject.of("name", newName));
    }

    @Test
    void testRenameProjectsOfRepositoryArchivesConflictingProjects() {
        // Given
        var oldName = "conflict-old";
        var newName = "conflict-new";
        var oldVcs = "git://github.com/heubeck/conflict-old.git";
        var oldWebsite = "https://github.com/heubeck/conflict-old";
        var newVcs = "git://github.com/heubeck/conflict-new.git";
        var newWebsite = "https://github.com/heubeck/conflict-new";

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", equalTo(oldName))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(JsonArray.of(
                        archiveProject("conflict-uuid-ok", oldName, oldVcs, oldWebsite, null),
                        archiveProject("conflict-uuid-taken", oldName + "-module", oldVcs, oldWebsite, "conflict-uuid-ok"),
                        archiveProject("conflict-uuid-inactive", oldName + "-other", oldVcs, oldWebsite, null).put("active", false)
                    ).encode()))
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/conflict-uuid-ok"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );
        // rename of the conflicting projects is rejected, deactivation is fine
        wiremock.register(
            patch(urlMatching("/api/v1/project/conflict-uuid-(taken|inactive)"))
                .withRequestBody(matchingJsonPath("$.name"))
                .willReturn(aResponse().withStatus(409).withBody("conflict"))
        );
        wiremock.register(
            patch(urlMatching("/api/v1/project/conflict-uuid-(taken|inactive)"))
                .withRequestBody(equalToJson("{\"active\":false}"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        // When
        var result = await(cut.renameProjectsOfRepository(oldName, newName, oldVcs, oldWebsite, newVcs, newWebsite));

        // Then
        // the inactive project is archived already and not touched again
        assertThat(result).isEqualTo(new DependencyTrackClient.RenameResult(1, 1, 1));
        wiremock.verifyThat(1, patchRequestedFor(urlEqualTo("/api/v1/project/conflict-uuid-taken"))
            .withRequestBody(equalToJson("{\"active\":false}")));
        wiremock.verifyThat(0, patchRequestedFor(urlEqualTo("/api/v1/project/conflict-uuid-inactive"))
            .withRequestBody(equalToJson("{\"active\":false}")));
    }

    @Test
    void testRenameProjectsOfRepositoryWithoutMatches() {
        // Given
        var oldName = "nomatch-old";
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", equalTo(oldName))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(JsonArray.of(
                        archiveProject("nomatch-uuid-foreign", oldName, "git://github.com/someone-else/nomatch-old.git", "https://github.com/someone-else/nomatch-old", null)
                    ).encode()))
        );

        // When
        var result = await(cut.renameProjectsOfRepository(oldName, "nomatch-new",
            "git://github.com/heubeck/nomatch-old.git", "https://github.com/heubeck/nomatch-old",
            "git://github.com/heubeck/nomatch-new.git", "https://github.com/heubeck/nomatch-new"));

        // Then
        assertThat(result).isEqualTo(new DependencyTrackClient.RenameResult(0, 0, 0));
        assertThat(wiremock.find(patchRequestedFor(urlMatching(".*")))).isEmpty();
    }

    @Test
    void testRenameExternalReferences() {
        var refs = JsonArray.of(
            JsonObject.of("type", "vcs", "url", "git://github.com/o/old.git"),
            JsonObject.of("type", "website", "url", "https://github.com/o/old"),
            JsonObject.of("type", "release-notes", "url", "https://github.com/o/old/releases"),
            JsonObject.of("type", "documentation", "url", "https://github.com/o/old-docs"),
            JsonObject.of("type", "issue-tracker", "url", "https://example.com/other")
        );

        var result = cut.renameExternalReferences(refs, "git://github.com/o/old.git", "https://github.com/o/old",
            "git://github.com/o/new.git", "https://github.com/o/new");

        assertThat(result).isEqualTo(JsonArray.of(
            JsonObject.of("type", "vcs", "url", "git://github.com/o/new.git"),
            JsonObject.of("type", "website", "url", "https://github.com/o/new"),
            JsonObject.of("type", "release-notes", "url", "https://github.com/o/new/releases"),
            // only whole path segments are replaced
            JsonObject.of("type", "documentation", "url", "https://github.com/o/old-docs"),
            JsonObject.of("type", "issue-tracker", "url", "https://example.com/other")
        ));
        assertThat(cut.renameExternalReferences(null, "a", "b", "c", "d")).isNull();
        assertThat(cut.renameExternalReferences(JsonArray.of(), "a", "b", "c", "d")).isNull();
    }

    @Test
    void testListProjectsWithPaging() {
        // Given
        var firstPage = new JsonArray();
        for (int i = 0; i < 100; i++) {
            firstPage.add(archiveProject("list-uuid-" + i, "list-module" + i, "git://github.com/heubeck/list.git", "https://github.com/heubeck/list", "list-uuid-parent"));
        }
        // a project showing up on both pages, because the list changed while paging
        var secondPage = JsonArray.of(
            archiveProject("list-uuid-0", "list-module0", "git://github.com/heubeck/list.git", "https://github.com/heubeck/list", "list-uuid-parent"),
            archiveProject("list-uuid-parent", "list", "git://github.com/heubeck/list.git", "https://github.com/heubeck/list", null)
                .put("lastBomImport", 1767232800000L)
        );

        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .withQueryParam("searchText", absent())
                .withQueryParam("excludeInactive", equalTo("true"))
                .withQueryParam("pageSize", equalTo("100"))
                .withQueryParam("pageNumber", equalTo("1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withHeader("X-Total-Count", "102").withBody(firstPage.encode()))
        );
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", absent())
                .withQueryParam("excludeInactive", equalTo("true"))
                .withQueryParam("pageNumber", equalTo("2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withHeader("X-Total-Count", "102").withBody(secondPage.encode()))
        );

        // When
        var projects = await(cut.listProjects(true));

        // Then
        assertThat(projects).hasSize(101);
        assertThat(projects).extracting(DependencyTrackProject::uuid).doesNotHaveDuplicates();
        var parent = projects.stream().filter(p -> p.uuid().equals("list-uuid-parent")).findFirst().orElseThrow();
        assertThat(parent.name()).isEqualTo("list");
        assertThat(parent.version()).isEqualTo("1.0.0");
        assertThat(parent.active()).isTrue();
        assertThat(parent.parentUuid()).isEmpty();
        // release notes aren't a reference to the repository itself
        assertThat(parent.repositoryUrls()).containsExactly("git://github.com/heubeck/list.git", "https://github.com/heubeck/list");
        assertThat(parent.lastBomImport()).contains(java.time.Instant.parse("2026-01-01T02:00:00Z"));
        var child = projects.stream().filter(p -> p.uuid().equals("list-uuid-0")).findFirst().orElseThrow();
        assertThat(child.parentUuid()).contains("list-uuid-parent");
        assertThat(child.lastBomImport()).isEmpty();
    }

    @Test
    void testListProjectsFailsIfProjectsCanNotBeListedCompletely() {
        // Given
        wiremock.register(
            get(urlPathEqualTo("/api/v1/project"))
                .withQueryParam("searchText", absent())
                .withQueryParam("excludeInactive", equalTo("false"))
                .willReturn(aResponse().withStatus(500).withBody("boom"))
        );

        // When
        var subscriber = cut.listProjects(false).subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure().assertFailedWith(DependencyTrackClientHttpException.class);
    }

    @Test
    void testArchiveProject() {
        // Given
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/single-archive-uuid"))
                .withHeader("X-API-Key", equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}"))
        );
        wiremock.register(
            patch(urlEqualTo("/api/v1/project/single-archive-conflict-uuid"))
                .willReturn(aResponse().withStatus(409).withBody("conflict"))
        );

        // When, Then
        assertThat(await(cut.archiveProject("single-archive-uuid"))).isTrue();
        assertThat(await(cut.archiveProject("single-archive-conflict-uuid"))).isFalse();
        var patched = wiremock.find(patchRequestedFor(urlEqualTo("/api/v1/project/single-archive-uuid")));
        assertThat(patched).hasSize(1);
        assertThat(new JsonObject(patched.get(0).getBodyAsString())).isEqualTo(JsonObject.of("active", false));
    }

    private static JsonObject archiveProject(String uuid, String name, String vcsUrl, String websiteUrl, String parentUuid) {
        var project = JsonObject.of(
            "uuid", uuid,
            "name", name,
            "version", "1.0.0",
            "active", true,
            "externalReferences", JsonArray.of(
                JsonObject.of("type", "vcs", "url", vcsUrl),
                JsonObject.of("type", "website", "url", websiteUrl),
                JsonObject.of("type", "release-notes", "url", websiteUrl + "/releases")
            )
        );
        if (parentUuid != null) {
            project.put("parent", JsonObject.of("uuid", parentUuid));
        }
        return project;
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
