package org.egov.idgen.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MetaResourceTest {

    @Test
    void listsTheSixTemplatesWithWhetherEachCanIssue() {
        given().when().get("/api/templates")
                .then().statusCode(200)
                .body("$", hasSize(6))
                .body("id", contains("PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT", "WS_CONNECTION", "BPA_PERMIT",
                        "EST_ALLOTMENT"))
                .body("find { it.id == 'PG_RECEIPT' }.format", equalTo("PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]"))
                .body("find { it.id == 'PG_RECEIPT' }.canIssue", equalTo(true))
                .body("find { it.id == 'PG_RECEIPT' }.reason", nullValue())
                .body("find { it.id == 'PGR_COMPLAINT' }.scope", equalTo("counter"))
                .body("find { it.id == 'WS_CONNECTION' }.counter", equalTo("SEQ_WS"))
                .body("find { it.id == 'EST_ALLOTMENT' }.canIssue", equalTo(false))
                .body("find { it.id == 'EST_ALLOTMENT' }.reason", equalTo("Counter SEQ_EST_ALLOT has no configuration"));
    }

    @Test
    void listsTheTenCities() {
        given().when().get("/api/cities")
                .then().statusCode(200)
                .body("$", hasSize(10))
                .body("tenantId", hasItem("pb.amritsar"))
                .body("find { it.cityCode == 'BLR' }.name", equalTo("Bengaluru"));
    }

    @Test
    void readinessReportsLoadedConfiguration() {
        given().when().get("/q/health/ready")
                .then().statusCode(200)
                .body("checks.find { it.name == 'Template and city configuration' }.data.templates", equalTo(6))
                .body("checks.find { it.name == 'Template and city configuration' }.data.cities", equalTo(10));
    }
}
