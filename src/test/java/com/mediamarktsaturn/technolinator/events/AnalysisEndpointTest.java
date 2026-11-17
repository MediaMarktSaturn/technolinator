package com.mediamarktsaturn.technolinator.events;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AnalysisEndpointTest {

    @Test
    void requiresExactlyOneRepository() {
        given().post("/analyze").then().statusCode(400).body("message", containsString("exactly once"));
        given().queryParam("repository", "a/b").queryParam("repository", "a/c").post("/analyze").then().statusCode(400);
    }

    @Test
    void rejectsRepositoriesWithoutOwner() {
        given().queryParam("repository", "app").post("/analyze").then().statusCode(400).body("message", containsString("owner/repo"));
        given().queryParam("repository", "").post("/analyze").then().statusCode(400);
        given().queryParam("repository", "a/b,c/d").post("/analyze").then().statusCode(400);
        given().queryParam("repository", "a/b/c").post("/analyze").then().statusCode(400);
        given().queryParam("repository", "a/..").post("/analyze").then().statusCode(400);
        given().queryParam("repository", "a/b c").post("/analyze").then().statusCode(400);
        given().queryParam("repository", "a/b\nforged log line").post("/analyze").then().statusCode(400);
    }

    @Test
    void onlyAcceptsPost() {
        given().get("/analyze").then().statusCode(404);
    }
}
