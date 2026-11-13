package org.egov.idgen.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PingResourceTest {

    @Test
    void pingReturnsOk() {
        given().when().get("/api/ping")
                .then().statusCode(200)
                .body("status", equalTo("ok"))
                .body("instance", notNullValue());
    }

    @Test
    void readinessIsUp() {
        given().when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }
}
