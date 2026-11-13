package org.egov.idgen.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.egov.idgen.config.CityRegistry;
import org.egov.idgen.config.TemplateRegistry;
import org.egov.idgen.template.CompiledTemplate;

/** Templates and cities for the UI dropdowns. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class MetaResource {

    @Inject
    TemplateRegistry templates;

    @Inject
    CityRegistry cities;

    /**
     * @param counter  the counter IDs are drawn from (template ID for {@code [SEQ:n]})
     * @param scope    what the counter is scoped by, e.g. {@code "counter + city + period"}
     * @param canIssue false when the named counter has no configuration
     * @param reason   why it can't issue, or null
     */
    public record TemplateView(String id, String name, String format, String counter, String scope,
                               boolean canIssue, String reason) {

        static TemplateView of(CompiledTemplate t) {
            String scope = "counter" + (t.printsCity() ? " + city" : "") + (t.printsPeriod() ? " + period" : "");
            return new TemplateView(t.id(), t.name(), t.format(), t.counterName(), scope, t.canIssue(),
                    t.canIssue() ? null : "Counter " + t.counterName() + " has no configuration");
        }
    }

    public record CityView(String tenantId, String cityCode, String name) {}

    @GET
    @Path("/templates")
    public List<TemplateView> templates() {
        return templates.all().stream().map(TemplateView::of).toList();
    }

    @GET
    @Path("/cities")
    public List<CityView> cities() {
        return cities.all().stream().map(c -> new CityView(c.tenantId(), c.code(), c.name())).toList();
    }
}
