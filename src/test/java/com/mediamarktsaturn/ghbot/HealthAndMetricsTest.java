package com.mediamarktsaturn.ghbot;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class HealthAndMetricsTest {

    @Test
    public void testLiveProbe() {
        given()
            .get("/q/health/live")
            .then()
            .statusCode(200);
    }

    @Test
    public void testReadyProbe() {
        given()
            .get("/q/health/ready")
            .then()
            .statusCode(200);
    }

    @Test
    public void testStartupProbe() {
        given()
            .get("/q/health/started")
            .then()
            .statusCode(200);
    }

    @Test
    public void testMetricsEndpoint() {
        given()
            .get("/q/metrics")
            .then()
            .statusCode(200);
    }
}
