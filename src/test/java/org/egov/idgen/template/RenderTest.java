package org.egov.idgen.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.egov.idgen.api.ErrorCode;
import org.egov.idgen.api.IdgenException;
import org.egov.idgen.config.City;
import org.egov.idgen.config.DataLoader;
import org.egov.idgen.config.TemplateRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Renders the six provided templates from data/templates.json, as delivered. */
class RenderTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final ZonedDateTime NOV_13_2026 = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, IST);
    static final City AMR = new City("pb.amritsar", "AMR", "Amritsar");
    static final City LDH = new City("pb.ludhiana", "LDH", "Ludhiana");

    static TemplateRegistry templates;

    @BeforeAll
    static void loadProvidedTemplates() {
        templates = TemplateRegistry.of(DataLoader.parseTemplates(Path.of("data/templates.json")));
    }

    static String first(String templateId, City city, ZonedDateTime now) {
        CompiledTemplate t = templates.get(templateId);
        return t.render(city, now, t.start());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PG_RECEIPT,    PG-AMR-2026-27-000001",
            "TL_LICENSE,    TL/AMR/2026/00001",
            "PGR_COMPLAINT, PGR-00000001",
            "WS_CONNECTION, WS-AMR-1000-11-2026",
            "BPA_PERMIT,    BPA-AMR-26-27-000001",
    })
    void firstIdForAmritsar(String templateId, String expected) {
        assertEquals(expected, first(templateId, AMR, NOV_13_2026));
    }

    @Test
    void estAllotmentIsRejectedBecauseItsCounterHasNoConfiguration() {
        CompiledTemplate est = templates.get("EST_ALLOTMENT");
        assertFalse(est.canIssue());
        IdgenException e = assertThrows(IdgenException.class, est::requireConfiguredCounter);
        assertEquals(ErrorCode.COUNTER_NOT_CONFIGURED, e.code());
        assertEquals(Map.of("templateId", "EST_ALLOTMENT", "counter", "SEQ_EST_ALLOT"), e.details());
        assertThrows(IdgenException.class, est::start);
    }

    @Test
    void sequencesAfterTheFirstCountUp() {
        CompiledTemplate pg = templates.get("PG_RECEIPT");
        assertEquals("PG-AMR-2026-27-000002", pg.render(AMR, NOV_13_2026, 2));
        assertEquals("PG-LDH-2026-27-000001", pg.render(LDH, NOV_13_2026, 1));
    }

    // ------------------------------------------------------------- financial year boundary

    @ParameterizedTest(name = "{0} -> {1} / {2}")
    @CsvSource({
            "2027-03-31T23:59:59, 2026-27, 26-27",
            "2027-04-01T00:00:00, 2027-28, 27-28",
            "2026-04-01T00:00:00, 2026-27, 26-27",
            "2026-01-15T12:00:00, 2025-26, 25-26",
            "2099-12-01T00:00:00, 2099-00, 99-00",
    })
    void financialYearTurnsOnFirstOfApril(String localIst, String longFy, String shortFy) {
        ZonedDateTime now = java.time.LocalDateTime.parse(localIst).atZone(IST);
        assertEquals(longFy, new Token.FinancialYear(true).render(now));
        assertEquals(shortFy, new Token.FinancialYear(false).render(now));
    }

    @Test
    void financialYearUsesIndianTimeNotUtc() {
        // 31 Mar 2027 20:00 UTC is already 1 Apr 2027 01:30 in India.
        ZonedDateTime now = Instant.parse("2027-03-31T20:00:00Z").atZone(IST);
        assertEquals("PG-AMR-2027-28-000001", first("PG_RECEIPT", AMR, now));
    }

    @Test
    void newFinancialYearMeansNewCounterKey() {
        CompiledTemplate pg = templates.get("PG_RECEIPT");
        CounterKey march = pg.counterKey(AMR, ZonedDateTime.of(2027, 3, 31, 23, 59, 0, 0, IST));
        CounterKey april = pg.counterKey(AMR, ZonedDateTime.of(2027, 4, 1, 0, 0, 0, 0, IST));
        assertEquals("2026-27", march.period());
        assertEquals("2027-28", april.period());
        assertNotEquals(march, april);
    }

    // ------------------------------------------------------------- padding growth

    @Test
    void paddingIsAMinimumWidthAndValuesGrowPastIt() {
        CompiledTemplate ws = templates.get("WS_CONNECTION");
        assertEquals("WS-AMR-9999-11-2026", ws.render(AMR, NOV_13_2026, 9_999));
        assertEquals("WS-AMR-10000-11-2026", ws.render(AMR, NOV_13_2026, 10_000));
        assertEquals("PG-AMR-2026-27-1234567", templates.get("PG_RECEIPT").render(AMR, NOV_13_2026, 1_234_567));
    }

    // ------------------------------------------------------------- counter keys (ADR-003)

    @Test
    void pgrHasOneGlobalKeyBecauseItsIdsPrintNoCity() {
        CompiledTemplate pgr = templates.get("PGR_COMPLAINT");
        CounterKey amr = pgr.counterKey(AMR, NOV_13_2026);
        CounterKey ldh = pgr.counterKey(LDH, NOV_13_2026);
        assertEquals(amr, ldh);
        assertNull(amr.cityCode());
        assertNull(amr.period(), "PGR prints no period, so it never resets");
        assertEquals(new CounterKey(CounterKey.Kind.TEMPLATE, "PGR_COMPLAINT", null, null), amr);
        assertFalse(pgr.render(AMR, NOV_13_2026, 1).contains("AMR"));
    }

    @Test
    void keysFollowWhatEachTemplatePrints() {
        assertEquals(new CounterKey(CounterKey.Kind.TEMPLATE, "PG_RECEIPT", "AMR", "2026-27"),
                templates.get("PG_RECEIPT").counterKey(AMR, NOV_13_2026));
        assertEquals(new CounterKey(CounterKey.Kind.TEMPLATE, "TL_LICENSE", "AMR", "2026"),
                templates.get("TL_LICENSE").counterKey(AMR, NOV_13_2026));
        assertEquals(new CounterKey(CounterKey.Kind.NAMED, "SEQ_WS", "AMR", "11-2026"),
                templates.get("WS_CONNECTION").counterKey(AMR, NOV_13_2026));
        assertEquals(new CounterKey(CounterKey.Kind.NAMED, "SEQ_BPA_PERMIT", "AMR", "26-27"),
                templates.get("BPA_PERMIT").counterKey(AMR, NOV_13_2026));
    }

    @Test
    void citiesCountSeparately() {
        CompiledTemplate pg = templates.get("PG_RECEIPT");
        assertNotEquals(pg.counterKey(AMR, NOV_13_2026), pg.counterKey(LDH, NOV_13_2026));
    }

    @Test
    void twoTemplatesSharingANamedCounterShareOneKey() {
        Map<String, CounterSpec> counters = Map.of("SEQ_SHARED", new CounterSpec(1));
        CompiledTemplate a = CompiledTemplate.compile("A", "A", "A-[CITY]-[SEQ_SHARED:4]", counters);
        CompiledTemplate b = CompiledTemplate.compile("B", "B", "B-[CITY]-[SEQ_SHARED:4]", counters);
        assertEquals(a.counterKey(AMR, NOV_13_2026), b.counterKey(AMR, NOV_13_2026));
    }

    @Test
    void namedCounterStartIsTheFirstValue() {
        assertEquals(1000, templates.get("WS_CONNECTION").start());
        assertEquals(1, templates.get("BPA_PERMIT").start());
        assertEquals(1, templates.get("PG_RECEIPT").start());
    }

    @Test
    void allSixProvidedTemplatesLoadInFileOrder() {
        assertEquals(List.of("PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT", "WS_CONNECTION", "BPA_PERMIT",
                        "EST_ALLOTMENT"),
                templates.all().stream().map(CompiledTemplate::id).toList());
        assertTrue(templates.all().stream().filter(t -> !t.id().equals("EST_ALLOTMENT"))
                .allMatch(CompiledTemplate::canIssue));
    }
}
