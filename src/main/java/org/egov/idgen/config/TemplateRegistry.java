package org.egov.idgen.config;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.egov.idgen.api.ErrorCode;
import org.egov.idgen.api.IdgenException;
import org.egov.idgen.template.CompiledTemplate;

/** Templates compiled from templates.json, in file order. */
@ApplicationScoped
public class TemplateRegistry {

    private volatile Map<String, CompiledTemplate> byId = Map.of();

    /** Replaces the registry contents. Called once at startup by {@link DataLoader}. */
    public void load(List<CompiledTemplate> templates) {
        Map<String, CompiledTemplate> index = new LinkedHashMap<>();
        templates.forEach(t -> index.put(t.id(), t));
        this.byId = java.util.Collections.unmodifiableMap(index);
    }

    /** @throws IdgenException UNKNOWN_TEMPLATE when no template has this ID */
    public CompiledTemplate get(String templateId) {
        CompiledTemplate t = templateId == null ? null : byId.get(templateId);
        if (t == null) {
            throw new IdgenException(ErrorCode.UNKNOWN_TEMPLATE,
                    "Template '" + templateId + "' does not exist",
                    Map.of("templateId", String.valueOf(templateId)));
        }
        return t;
    }

    public List<CompiledTemplate> all() {
        return List.copyOf(byId.values());
    }

    public static TemplateRegistry of(List<CompiledTemplate> templates) {
        TemplateRegistry r = new TemplateRegistry();
        r.load(templates);
        return r;
    }
}
