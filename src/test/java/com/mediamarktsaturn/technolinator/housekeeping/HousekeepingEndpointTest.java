package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

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
    void refusesToRunWithoutEnabledAction() {
        given().post("/housekeeping").then().statusCode(409).body("message", containsString("no enabled action"));
        given().queryParam("repository", "a/b").post("/housekeeping").then().statusCode(409);
    }

    @Test
    void onlyAcceptsPost() {
        given().get("/housekeeping").then().statusCode(404);
    }
}
