package org.egov.idgen.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.ZoneId;

/** The service's own settings (application.yaml, prefix {@code idgen}). */
@ConfigMapping(prefix = "idgen")
public interface IdgenConfig {

    Data data();

    /** Preferred block size for new sequences. Allocation always uses the sequence's real increment. */
    @WithDefault("100")
    int blockSize();

    @WithDefault("1000")
    int bulkMax();

    /** Clock for FY and date placeholders. */
    @WithDefault("Asia/Kolkata")
    ZoneId zone();

    interface Data {
        String templatesPath();

        String citiesPath();
    }
}
