package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class HousekeepingEndpointTest {

    @Test
    void rejectsParametersWithoutValue() {
        given().queryParam("repository", " , ").post("/housekeeping").then().statusCode(400);
        given().queryParam("project", "").post("/housekeeping").then().statusCode(400);
    }

    @Test
    void rejectsRepositoriesWithoutOwner() {
        given().queryParam("repository", "app").post("/housekeeping").then().statusCode(400).body("message", containsString("owner/repo"));
        given().queryParam("repository", "a/b,app").post("/housekeeping").then().statusCode(400);
        given().queryParam("repository", "a/..").post("/housekeeping").then().statusCode(400);
        given().queryParam("repository", "a/b c").post("/housekeeping").then().statusCode(400);
    }

    @Test
    void rejectsProjectsWithControlCharacters() {
        given().queryParam("project", "app\nforged log line").post("/housekeeping").then().statusCode(400).body("message", containsString("control characters"));
    }

    @Test
    void rejectsTooLargeSelection() {
        var projects = IntStream.range(0, HousekeepingEndpoint.MAX_SELECTED).mapToObj(i -> "p" + i).collect(Collectors.joining(","));
        given().queryParam("project", projects).queryParam("repository", "a/b").post("/housekeeping").then().statusCode(400).body("message", containsString("At most"));
        // duplicates are counted once
        given().queryParam("project", projects + ",p0").post("/housekeeping").then().statusCode(409);
    }

    @Test
    void refusesToRunWithoutEnabledAction() {
        given().post("/housekeeping").then().statusCode(409).body("message", containsString("no enabled action"));
        given().queryParam("repository", "a/b").post("/housekeeping").then().statusCode(409);
    }

    @Test
    void onlyAcceptsPost() {
        given().get("/housekeeping").then().statusCode(404);
    }
}
