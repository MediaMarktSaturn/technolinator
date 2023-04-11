package com.mediamarktsaturn.technolinator;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HealthAndMetricsTest {

    @Test
    void testLiveProbe() {
        given()
            .get("/q/health/live")
            .then()
            .statusCode(200);
    }

    @Test
    void testReadyProbe() {
        given()
            .get("/q/health/ready")
            .then()
            .statusCode(200);
    }

    @Test
    void testStartupProbe() {
        given()
            .get("/q/health/started")
            .then()
            .statusCode(200);
    }

    @Test
    void testMetricsEndpoint() {
        given()
            .get("/q/metrics")
            .then()
            .statusCode(200);
    }
}
