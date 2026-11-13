package org.egov.idgen.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.PreventFurtherStepsException;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.egov.idgen.template.CompiledTemplate;
import org.egov.idgen.template.CounterSpec;
import org.egov.idgen.template.TemplateParseException;
import org.jboss.logging.Logger;

/**
 * Reads data/templates.json and data/cities.csv once at startup and fills the registries. The files are
 * configuration, used as delivered; any problem stops startup with a message naming the file and the line
 * (or the template).
 */
@Startup
@ApplicationScoped
public class DataLoader {

    private static final Logger LOG = Logger.getLogger(DataLoader.class);
    private static final String CSV_HEADER = "tenant_id,city_code,city_name";
    /** A city code is printed into IDs, so it must not contain separators that could make two IDs collide. */
    private static final Pattern CITY_CODE = Pattern.compile("[A-Za-z0-9]+");

    @Inject
    IdgenConfig config;

    @Inject
    TemplateRegistry templates;

    @Inject
    CityRegistry cities;

    @PostConstruct
    void load() {
        List<City> cityList;
        List<CompiledTemplate> templateList;
        try {
            cityList = parseCities(Path.of(config.data().citiesPath()));
            templateList = parseTemplates(Path.of(config.data().templatesPath()));
        } catch (ConfigException e) {
            // Stop startup with one clear line instead of Quarkus's wrapped stack trace.
            LOG.error("Invalid configuration: " + e.getMessage());
            throw new PreventFurtherStepsException("Invalid configuration: " + e.getMessage(), 1);
        }
        cities.load(cityList);
        templates.load(templateList);
        LOG.infof("Loaded %d templates and %d cities", templateList.size(), cityList.size());
        templateList.stream().filter(t -> !t.canIssue()).forEach(t -> LOG.warnf(
                "Template %s uses counter %s, which has no configuration; requests for it will fail",
                t.id(), t.counterName()));
    }

    // ---------------------------------------------------------------- cities.csv

    public static List<City> parseCities(Path file) {
        String text = read(file);
        String[] lines = text.split("\r?\n", -1);
        if (lines.length == 0 || !lines[0].trim().equalsIgnoreCase(CSV_HEADER)) {
            throw new ConfigException(file + " line 1: expected header '" + CSV_HEADER + "'");
        }
        List<City> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != 3) {
                throw new ConfigException(file + " line " + lineNo + ": expected 3 fields, found " + f.length);
            }
            String tenant = f[0].trim();
            String code = f[1].trim();
            String name = f[2].trim();
            if (tenant.isEmpty() || code.isEmpty() || name.isEmpty()) {
                throw new ConfigException(file + " line " + lineNo + ": empty field");
            }
            if (!CITY_CODE.matcher(code).matches()) {
                throw new ConfigException(file + " line " + lineNo + ": city code '" + code
                        + "' must be letters and digits only");
            }
            for (String key : List.of(tenant, code)) {
                Integer prev = seen.putIfAbsent(CityRegistry.norm(key), lineNo);
                if (prev != null) {
                    throw new ConfigException(file + " line " + lineNo + ": '" + key
                            + "' already used on line " + prev);
                }
            }
            out.add(new City(tenant, code, name));
        }
        if (out.isEmpty()) {
            throw new ConfigException(file + ": no cities");
        }
        return out;
    }

    // ---------------------------------------------------------------- templates.json

    public static List<CompiledTemplate> parseTemplates(Path file) {
        String text = read(file);
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(text);
        } catch (JsonProcessingException e) {
            JsonLocation loc = e.getLocation();
            String where = loc == null ? "" : " line " + loc.getLineNr() + ", column " + loc.getColumnNr();
            throw new ConfigException(file + where + ": invalid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ConfigException(file + ": expected a JSON object");
        }

        Map<String, CounterSpec> counters = new LinkedHashMap<>();
        JsonNode countersNode = root.path("counters");
        if (!countersNode.isMissingNode() && !countersNode.isObject()) {
            throw new ConfigException(file + ": 'counters' must be an object");
        }
        for (Map.Entry<String, JsonNode> e : countersNode.properties()) {
            JsonNode start = e.getValue().path("start");
            if (!start.isMissingNode() && !start.canConvertToLong()) {
                throw new ConfigException(file + ": counters." + e.getKey() + ".start must be a whole number");
            }
            long s = start.isMissingNode() ? 1L : start.asLong();
            if (s < 0) {
                throw new ConfigException(file + ": counters." + e.getKey() + ".start must not be negative");
            }
            counters.put(e.getKey(), new CounterSpec(s));
        }

        JsonNode list = root.path("templates");
        if (!list.isArray() || list.isEmpty()) {
            throw new ConfigException(file + ": 'templates' must be a non-empty array");
        }
        List<CompiledTemplate> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode t = list.get(i);
            String id = t.path("id").asText("").trim();
            String where = file + ": templates[" + i + "]" + (id.isEmpty() ? "" : " (" + id + ")");
            if (id.isEmpty()) {
                throw new ConfigException(where + ": missing 'id'");
            }
            if (!ids.add(id)) {
                throw new ConfigException(where + ": duplicate template id");
            }
            String format = t.path("format").asText("");
            if (format.isEmpty()) {
                throw new ConfigException(where + ": missing 'format'");
            }
            String name = t.path("name").asText(id);
            try {
                out.add(CompiledTemplate.compile(id, name, format, counters));
            } catch (TemplateParseException e) {
                throw new ConfigException(file + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static String read(Path file) {
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            return !s.isEmpty() && s.charAt(0) == 0xFEFF ? s.substring(1) : s; // strip a UTF-8 byte-order mark
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + file.toAbsolutePath() + ": " + e, e);
        }
    }
}
