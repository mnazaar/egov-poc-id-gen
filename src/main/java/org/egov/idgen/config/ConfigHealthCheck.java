package org.egov.idgen.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Readiness: templates and cities are loaded. The Postgres check comes from the reactive client. */
@Readiness
@ApplicationScoped
public class ConfigHealthCheck implements HealthCheck {

    @Inject
    TemplateRegistry templates;

    @Inject
    CityRegistry cities;

    @Override
    public HealthCheckResponse call() {
        int t = templates.all().size();
        int c = cities.all().size();
        return HealthCheckResponse.named("Template and city configuration")
                .status(t > 0 && c > 0)
                .withData("templates", t)
                .withData("cities", c)
                .build();
    }
}
