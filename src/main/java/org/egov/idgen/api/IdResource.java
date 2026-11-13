package org.egov.idgen.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.egov.idgen.config.CityRegistry;
import org.egov.idgen.config.IdgenConfig;
import org.egov.idgen.config.TemplateRegistry;
import org.egov.idgen.issue.IdService;
import org.egov.idgen.issue.IdService.Issued;
import org.egov.idgen.recent.RecentIds;

/** Issue endpoints. Runs on worker threads: a block refill waits on Postgres. */
@Path("/api/ids")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class IdResource {

    static final int RECENT_MAX = 200;

    public record IssueResponse(String id, String templateId, String tenantId, String cityCode, String issuedAt) {}

    public record BulkResponse(List<String> ids, int count, String templateId, String tenantId, String cityCode,
                               String issuedAt) {}

    public record RecentResponse(String id, String templateId, String tenantId, String cityCode, String issuedAt) {}

    @Inject
    IdService service;

    @Inject
    RecentIds recent;

    @Inject
    TemplateRegistry templates;

    @Inject
    CityRegistry cities;

    @Inject
    IdgenConfig config;

    @POST
    public IssueResponse issueOne(JsonNode body) {
        requireObject(body);
        Issued r = service.issue(requiredText(body, "templateId"), requiredText(body, "city"), 1);
        return new IssueResponse(r.ids().getFirst(), r.template().id(), r.city().tenantId(), r.city().code(),
                r.issuedAtText());
    }

    @POST
    @Path("/bulk")
    public BulkResponse issueBulk(JsonNode body) {
        requireObject(body);
        String templateId = requiredText(body, "templateId");
        String city = requiredText(body, "city");
        int count = count(body.get("count"));
        Issued r = service.issue(templateId, city, count);
        return new BulkResponse(r.ids(), r.ids().size(), r.template().id(), r.city().tenantId(), r.city().code(),
                r.issuedAtText());
    }

    /** Recently issued IDs on the instance that serves the request, newest first. */
    @GET
    @Path("/recent")
    public List<RecentResponse> recent(@QueryParam("templateId") String templateId, @QueryParam("city") String city,
                                       @QueryParam("limit") String limit) {
        String tpl = blank(templateId) ? null : templates.get(templateId).id();
        String code = blank(city) ? null : cities.resolve(city).code();
        return recent.recent(tpl, code, limit(limit)).stream()
                .map(e -> new RecentResponse(e.id(), e.templateId(), e.tenantId(), e.cityCode(), e.issuedAt()))
                .toList();
    }

    // ---------------------------------------------------------------- validation

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IdgenException(ErrorCode.VALIDATION_ERROR, "Request body must be a JSON object");
        }
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IdgenException(ErrorCode.VALIDATION_ERROR, "'" + field + "' is required and must be a string",
                    Map.of("field", field));
        }
        return v.asText().trim();
    }

    private int count(JsonNode v) {
        int max = config.bulkMax();
        if (v == null || !v.canConvertToInt() || !v.isIntegralNumber() || v.asInt() < 1 || v.asInt() > max) {
            throw new IdgenException(ErrorCode.INVALID_COUNT, "'count' must be a whole number from 1 to " + max,
                    Map.of("min", 1, "max", max, "count", v == null ? "missing" : v.toString()));
        }
        return v.asInt();
    }

    private static int limit(String limit) {
        if (blank(limit)) {
            return RecentIds.PER_KEY;
        }
        try {
            int n = Integer.parseInt(limit.trim());
            if (n >= 1 && n <= RECENT_MAX) {
                return n;
            }
        } catch (NumberFormatException e) {
            // fall through to the error below
        }
        throw new IdgenException(ErrorCode.VALIDATION_ERROR, "'limit' must be a whole number from 1 to " + RECENT_MAX,
                Map.of("field", "limit"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
