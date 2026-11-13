package org.egov.idgen.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The static UI is served at / by the same app (ADR-002) and calls only the documented endpoints. */
@QuarkusTest
class UiTest {

    @Test
    void indexPageIsServedAtRoot() {
        given().when().get("/")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("<title>ID Generator</title>"))
                .body(containsString("/api/templates"))
                .body(containsString("/api/cities"))
                .body(containsString("/api/ids/bulk"))
                .body(containsString("/api/ids/recent"))
                .body(containsString("Recently issued on this instance"));
    }
}
