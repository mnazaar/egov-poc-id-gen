package org.egov.idgen.sequence;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import org.egov.idgen.api.IdgenException;
import org.egov.idgen.config.City;
import org.egov.idgen.config.CityRegistry;
import org.egov.idgen.config.DataLoader;
import org.egov.idgen.config.IdgenConfig;
import org.egov.idgen.config.TemplateRegistry;
import org.egov.idgen.issue.IdService;
import org.egov.idgen.template.CompiledTemplate;

/**
 * Creates the sequence for every issuable template × city in the current period at startup, so the first
 * requests don't pay for {@code CREATE SEQUENCE}. Best effort: anything missed (Postgres down at startup, a
 * new period) is created on first use.
 */
@ApplicationScoped
public class SequenceWarmup {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(SequenceWarmup.class);

    @Inject
    DataLoader dataLoader;

    @Inject
    TemplateRegistry templates;

    @Inject
    CityRegistry cities;

    @Inject
    SequenceStore store;

    @Inject
    IdService ids;

    @Inject
    IdgenConfig config;

    void onStart(@Observes StartupEvent ev) {
        // Startup observers run in no guaranteed order; this call loads the config if it isn't yet.
        if (!dataLoader.isLoaded()) {
            LOG.warn("Configuration not loaded; skipping sequence warm-up");
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(config.zone());
        Set<String> names = new HashSet<>();
        try {
            for (CompiledTemplate tpl : templates.all()) {
                if (!tpl.canIssue()) {
                    continue;
                }
                for (City city : cities.all()) {
                    SequenceSpec spec = ids.specFor(tpl, tpl.counterKey(city, now));
                    if (names.add(spec.name())) {
                        store.ensure(spec);
                    }
                }
            }
            LOG.infof("Sequences ready: %d", names.size());
        } catch (IdgenException e) {
            LOG.warnf("Could not create sequences at startup (%s); they will be created on first use",
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }
}
