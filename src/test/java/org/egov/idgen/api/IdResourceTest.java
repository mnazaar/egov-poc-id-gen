package org.egov.idgen.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.egov.idgen.template.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class IdResourceTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> TEMPLATES = List.of("PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT", "WS_CONNECTION",
            "BPA_PERMIT");
    static final List<String[]> CITIES = List.of(
            new String[] {"pb.amritsar", "AMR"}, new String[] {"pb.ludhiana", "LDH"},
            new String[] {"pb.jalandhar", "JAL"}, new String[] {"pb.patiala", "PTA"},
            new String[] {"ka.bengaluru", "BLR"}, new String[] {"ka.mysuru", "MYS"},
            new String[] {"mh.pune", "PUN"}, new String[] {"mh.nagpur", "NGP"},
            new String[] {"tn.chennai", "CHN"}, new String[] {"tn.madurai", "MDU"});

    @TestHTTPResource
    URL baseUrl;

    @Inject
    Pool pool;

    /** The ID pattern for a template and city code, using today's date in India. */
    static String pattern(String templateId, String code) {
        ZonedDateTime now = ZonedDateTime.now(IST);
        String fy = new Token.FinancialYear(true).render(now);
        String fyShort = new Token.FinancialYear(false).render(now);
        return switch (templateId) {
            case "PG_RECEIPT" -> "PG-" + code + "-" + fy + "-\\d{6,}";
            case "TL_LICENSE" -> "TL/" + code + "/" + now.getYear() + "/\\d{5,}";
            case "PGR_COMPLAINT" -> "PGR-\\d{8,}";
            case "WS_CONNECTION" -> "WS-" + code + "-\\d{4,}-" + DateTimeFormatter.ofPattern("MM-yyyy").format(now);
            case "BPA_PERMIT" -> "BPA-" + code + "-" + fyShort + "-\\d{6,}";
            default -> throw new IllegalArgumentException(templateId);
        };
    }

    static Stream<Arguments> everyTemplateAndCity() {
        return TEMPLATES.stream().flatMap(t -> CITIES.stream().map(c -> Arguments.of(t, c[0], c[1])));
    }

    static ValidatableResponse post(String path, String json) {
        return given().contentType(ContentType.JSON).body(json).when().post(path).then();
    }

    static ValidatableResponse error(ValidatableResponse r, int status, String code) {
        return r.statusCode(status)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo(code))
                .body("error.message", notNullValue())
                .body("error", hasKey("details"))
                .body("$", not(hasKey("stackTrace")))
                .body("error.message", not(matchesPattern("(?s).*(Exception|\\tat ).*")));
    }

    // ------------------------------------------------------------- single and bulk, every template × city

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyTemplateAndCity")
    void singleIssueWorksForEveryTemplateAndCity(String templateId, String tenantId, String code) {
        post("/api/ids", "{\"templateId\":\"" + templateId + "\",\"city\":\"" + tenantId + "\"}")
                .statusCode(200)
                .body("id", matchesPattern(pattern(templateId, code)))
                .body("templateId", equalTo(templateId))
                .body("tenantId", equalTo(tenantId))
                .body("cityCode", equalTo(code))
                .body("issuedAt", matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+05:30"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyTemplateAndCity")
    void bulkIssueWorksForEveryTemplateAndCity(String templateId, String tenantId, String code) {
        List<String> ids = post("/api/ids/bulk",
                "{\"templateId\":\"" + templateId + "\",\"city\":\"" + code + "\",\"count\":3}")
                .statusCode(200)
                .body("count", equalTo(3))
                .body("ids", hasSize(3))
                .body("tenantId", equalTo(tenantId))
                .extract().jsonPath().getList("ids", String.class);
        assertEquals(3, new HashSet<>(ids).size());
        ids.forEach(id -> assertTrue(id.matches(pattern(templateId, code)), id));
    }

    @Test
    void largeBulkCrossesManyBlocksWithoutDuplicates() {
        List<String> ids = post("/api/ids/bulk", "{\"templateId\":\"WS_CONNECTION\",\"city\":\"BLR\",\"count\":1000}")
                .statusCode(200).body("count", equalTo(1000))
                .extract().jsonPath().getList("ids", String.class);
        assertEquals(1000, new HashSet<>(ids).size());
    }

    @Test
    void consecutiveIdsOnOneInstanceCountUp() {
        String first = post("/api/ids", "{\"templateId\":\"BPA_PERMIT\",\"city\":\"NGP\"}")
                .statusCode(200).extract().path("id");
        String second = post("/api/ids", "{\"templateId\":\"BPA_PERMIT\",\"city\":\"NGP\"}")
                .statusCode(200).extract().path("id");
        assertEquals(seq(first) + 1, seq(second), first + " then " + second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pb.patiala", "PTA", "pta", "PB.PATIALA"})
    void cityAcceptsTenantIdOrCodeInAnyCase(String city) {
        post("/api/ids", "{\"templateId\":\"TL_LICENSE\",\"city\":\"" + city + "\"}")
                .statusCode(200)
                .body("tenantId", equalTo("pb.patiala"))
                .body("cityCode", equalTo("PTA"));
    }

    @Test
    void pgrNumbersAreSharedAcrossCities() {
        long a = seq(post("/api/ids", "{\"templateId\":\"PGR_COMPLAINT\",\"city\":\"AMR\"}").extract().path("id"));
        long b = seq(post("/api/ids", "{\"templateId\":\"PGR_COMPLAINT\",\"city\":\"CHN\"}").extract().path("id"));
        assertEquals(a + 1, b, "one global counter, so Chennai continues where Amritsar left off");
    }

    @Test
    void parallelRequestsNeverDuplicate() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int w = 0; w < 16; w++) {
            int worker = w;
            futures.add(pool.submit(() -> {
                List<String> got = new ArrayList<>();
                for (int i = 0; i < 25; i++) {
                    String template = TEMPLATES.get((worker + i) % TEMPLATES.size());
                    boolean bulk = i % 3 == 0;
                    String json = "{\"templateId\":\"" + template + "\",\"city\":\"MDU\""
                            + (bulk ? ",\"count\":" + (1 + i % 40) : "") + "}";
                    String base = baseUrl.toString().replaceAll("/$", "");
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                                    URI.create(base + (bulk ? "/api/ids/bulk" : "/api/ids")))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, r.statusCode(), r.body());
                    JsonNode body = JSON.readTree(r.body());
                    if (bulk) {
                        body.get("ids").forEach(id -> got.add(id.asText()));
                    } else {
                        got.add(body.get("id").asText());
                    }
                }
                return got;
            }));
        }
        List<String> all = new ArrayList<>();
        for (Future<List<String>> f : futures) {
            all.addAll(f.get());
        }
        pool.shutdown();
        assertTrue(all.size() > 400, "expected plenty of IDs, got " + all.size());
        assertEquals(all.size(), new HashSet<>(all).size(), "duplicate IDs issued");
    }

    // ------------------------------------------------------------- sequences in Postgres

    @Test
    void sequencesAreCreatedAtStartupWithTheRightBlockSize() {
        post("/api/ids", "{\"templateId\":\"WS_CONNECTION\",\"city\":\"AMR\"}").statusCode(200);
        Map<String, Long> increments = new HashMap<>();
        for (Row r : pool.query("SELECT sequencename, increment_by FROM pg_sequences").execute().await().indefinitely()) {
            increments.put(r.getString(0), r.getLong(1));
        }
        String fy = new Token.FinancialYear(true).render(ZonedDateTime.now(IST)).replace('-', '_');
        String month = DateTimeFormatter.ofPattern("MM_yyyy").format(ZonedDateTime.now(IST));
        assertEquals(100L, increments.get("seq_pg_receipt_amr_" + fy));
        assertEquals(100L, increments.get("seq_pgr_complaint"));
        assertEquals(10L, increments.get("seq_ws_amr_" + month), "narrow counter gets small blocks");
        assertEquals(41, increments.keySet().stream().filter(n -> n.startsWith("seq_")).count(),
                "4 templates x 10 cities + 1 global PGR; none for EST_ALLOTMENT. Got " + increments.keySet());
    }

    // ------------------------------------------------------------- recent IDs

    @Test
    void recentShowsNewestFirstWithFilters() {
        List<String> ids = post("/api/ids/bulk", "{\"templateId\":\"TL_LICENSE\",\"city\":\"mys\",\"count\":3}")
                .statusCode(200).extract().jsonPath().getList("ids", String.class);
        given().queryParam("templateId", "TL_LICENSE").queryParam("city", "ka.mysuru").queryParam("limit", 3)
                .when().get("/api/ids/recent")
                .then().statusCode(200)
                .body("id", contains(ids.get(2), ids.get(1), ids.get(0)))
                .body("[0].cityCode", equalTo("MYS"))
                .body("[0].tenantId", equalTo("ka.mysuru"))
                .body("[0].templateId", equalTo("TL_LICENSE"));
    }

    @Test
    void recentDefaultsToFiftyAcrossEverything() {
        post("/api/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"PUN\",\"count\":60}").statusCode(200);
        given().when().get("/api/ids/recent").then().statusCode(200).body("$", hasSize(50));
    }

    // ------------------------------------------------------------- every error code, one shape

    @Test
    void unknownTemplate() {
        error(post("/api/ids", "{\"templateId\":\"NOPE\",\"city\":\"AMR\"}"), 404, "UNKNOWN_TEMPLATE")
                .body("error.details.templateId", equalTo("NOPE"));
    }

    @Test
    void unknownCity() {
        error(post("/api/ids", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"XYZ\"}"), 404, "UNKNOWN_CITY")
                .body("error.details.city", equalTo("XYZ"));
    }

    @Test
    void counterNotConfigured() {
        error(post("/api/ids", "{\"templateId\":\"EST_ALLOTMENT\",\"city\":\"AMR\"}"), 422, "COUNTER_NOT_CONFIGURED")
                .body("error.message", equalTo("Template EST_ALLOTMENT uses counter SEQ_EST_ALLOT, which has no configuration"))
                .body("error.details.templateId", equalTo("EST_ALLOTMENT"))
                .body("error.details.counter", equalTo("SEQ_EST_ALLOT"));
        error(post("/api/ids/bulk", "{\"templateId\":\"EST_ALLOTMENT\",\"city\":\"AMR\",\"count\":2}"), 422,
                "COUNTER_NOT_CONFIGURED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1001", "\"5\"", "2.5", "null", "true", "99999999999"})
    void invalidCount(String count) {
        error(post("/api/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"AMR\",\"count\":" + count + "}"),
                400, "INVALID_COUNT")
                .body("error.details.max", equalTo(1000));
    }

    @Test
    void missingCount() {
        error(post("/api/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"AMR\"}"), 400, "INVALID_COUNT");
    }

    @Test
    void boundaryCountsAreAccepted() {
        post("/api/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"JAL\",\"count\":1}").statusCode(200)
                .body("count", equalTo(1));
        post("/api/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"JAL\",\"count\":1000}").statusCode(200)
                .body("count", equalTo(1000));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{bad json",
            "[]",
            "\"text\"",
            "{}",
            "{\"templateId\":\"PG_RECEIPT\"}",
            "{\"city\":\"AMR\"}",
            "{\"templateId\":42,\"city\":\"AMR\"}",
            "{\"templateId\":\"  \",\"city\":\"AMR\"}"})
    void validationError(String body) {
        error(post("/api/ids", body), 400, "VALIDATION_ERROR");
    }

    @Test
    void validationErrorNamesTheMissingField() {
        error(post("/api/ids", "{\"templateId\":\"PG_RECEIPT\"}"), 400, "VALIDATION_ERROR")
                .body("error.details.field", equalTo("city"));
    }

    @Test
    void validationIsCheckedBeforeLookups() {
        error(post("/api/ids/bulk", "{\"templateId\":\"NOPE\",\"city\":\"AMR\",\"count\":0}"), 400, "INVALID_COUNT");
    }

    @Test
    void frameworkErrorsUseTheSameShape() {
        error(given().when().get("/api/nope").then(), 404, "NOT_FOUND");
        error(given().when().get("/api/ids").then(), 405, "METHOD_NOT_ALLOWED");
        error(given().contentType(ContentType.TEXT).body("hi").when().post("/api/ids").then(), 415,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void recentRejectsBadFilters() {
        error(given().queryParam("limit", "abc").when().get("/api/ids/recent").then(), 400, "VALIDATION_ERROR");
        error(given().queryParam("limit", "0").when().get("/api/ids/recent").then(), 400, "VALIDATION_ERROR");
        error(given().queryParam("city", "XYZ").when().get("/api/ids/recent").then(), 404, "UNKNOWN_CITY");
        error(given().queryParam("templateId", "NOPE").when().get("/api/ids/recent").then(), 404, "UNKNOWN_TEMPLATE");
    }

    private static long seq(String id) {
        Matcher m = Pattern.compile("(\\d+)$").matcher(id.replaceAll("-\\d\\d-\\d{4}$", ""));
        assertTrue(m.find(), id);
        return Long.parseLong(m.group(1));
    }
}
