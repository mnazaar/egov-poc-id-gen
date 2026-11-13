package org.egov.idgen.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.egov.idgen.api.ErrorCode;
import org.egov.idgen.api.IdgenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataLoaderTest {

    @TempDir
    Path tmp;

    Path write(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ------------------------------------------------------------- cities.csv

    @Test
    void loadsTheProvidedCitiesFileWithItsWindowsLineEndings() {
        List<City> cities = DataLoader.parseCities(Path.of("data/cities.csv"));
        assertEquals(10, cities.size());
        assertEquals(new City("pb.amritsar", "AMR", "Amritsar"), cities.getFirst());
        assertEquals(new City("tn.madurai", "MDU", "Madurai"), cities.getLast());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pb.amritsar", "AMR", "amr", "PB.AMRITSAR", " Amr "})
    void resolvesTenantIdOrCityCodeCaseInsensitively(String name) {
        CityRegistry registry = CityRegistry.of(DataLoader.parseCities(Path.of("data/cities.csv")));
        assertEquals("pb.amritsar", registry.resolve(name).tenantId());
    }

    @Test
    void unknownCityIsAStructuredError() {
        CityRegistry registry = CityRegistry.of(DataLoader.parseCities(Path.of("data/cities.csv")));
        IdgenException e = assertThrows(IdgenException.class, () -> registry.resolve("XYZ"));
        assertEquals(ErrorCode.UNKNOWN_CITY, e.code());
        assertEquals("XYZ", e.details().get("city"));
        assertSame(ErrorCode.UNKNOWN_CITY, assertThrows(IdgenException.class, () -> registry.resolve(null)).code());
    }

    @Test
    void badCsvLineNamesFileAndLine() throws IOException {
        Path f = write("cities.csv", "tenant_id,city_code,city_name\npb.a,AAA,A\npb.b,BBB\n");
        ConfigException e = assertThrows(ConfigException.class, () -> DataLoader.parseCities(f));
        assertTrue(e.getMessage().contains("line 3: expected 3 fields"), e.getMessage());
    }

    @Test
    void duplicateCityCodeIsRejected() throws IOException {
        Path f = write("cities.csv", "tenant_id,city_code,city_name\npb.a,AAA,A\npb.b,aaa,B\n");
        ConfigException e = assertThrows(ConfigException.class, () -> DataLoader.parseCities(f));
        assertTrue(e.getMessage().contains("line 3: 'aaa' already used on line 2"), e.getMessage());
    }

    @Test
    void cityCodeWithASeparatorIsRejected() throws IOException {
        Path f = write("cities.csv", "tenant_id,city_code,city_name\npb.a,A-B,A\n");
        ConfigException e = assertThrows(ConfigException.class, () -> DataLoader.parseCities(f));
        assertTrue(e.getMessage().contains("line 2: city code 'A-B'"), e.getMessage());
    }

    @Test
    void wrongHeaderIsRejected() throws IOException {
        Path f = write("cities.csv", "id,code,name\npb.a,AAA,A\n");
        assertThrows(ConfigException.class, () -> DataLoader.parseCities(f));
    }

    @Test
    void missingFileNamesThePath() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> DataLoader.parseCities(tmp.resolve("nope.csv")));
        assertTrue(e.getMessage().contains("nope.csv"), e.getMessage());
    }

    // ------------------------------------------------------------- templates.json

    @Test
    void invalidJsonNamesLineAndColumn() throws IOException {
        Path f = write("templates.json", "{\n  \"templates\": [\n    { \"id\": \"A\", }\n  ]\n}\n");
        ConfigException e = assertThrows(ConfigException.class, () -> DataLoader.parseTemplates(f));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }

    @Test
    void badPlaceholderNamesTemplateAndPosition() throws IOException {
        Path f = write("templates.json", """
                {"templates": [{"id": "X", "format": "X-[CITY]-[BOGUS]-[SEQ:3]"}]}
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> DataLoader.parseTemplates(f));
        assertTrue(e.getMessage().contains("Template X, position 10: unknown placeholder [BOGUS]"), e.getMessage());
    }

    @Test
    void duplicateTemplateIdIsRejected() throws IOException {
        Path f = write("templates.json", """
                {"templates": [{"id": "X", "format": "X-[SEQ:3]"}, {"id": "X", "format": "Y-[SEQ:3]"}]}
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> DataLoader.parseTemplates(f));
        assertTrue(e.getMessage().contains("templates[1] (X): duplicate template id"), e.getMessage());
    }

    @Test
    void unknownTemplateIsAStructuredError() {
        TemplateRegistry registry = TemplateRegistry.of(DataLoader.parseTemplates(Path.of("data/templates.json")));
        IdgenException e = assertThrows(IdgenException.class, () -> registry.get("NOPE"));
        assertEquals(ErrorCode.UNKNOWN_TEMPLATE, e.code());
    }
}
