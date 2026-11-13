package org.egov.idgen.issue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.egov.idgen.config.City;
import org.egov.idgen.config.CityRegistry;
import org.egov.idgen.config.IdgenConfig;
import org.egov.idgen.config.TemplateRegistry;
import org.egov.idgen.recent.RecentIds;
import org.egov.idgen.sequence.BlockAllocator;
import org.egov.idgen.sequence.SequenceNames;
import org.egov.idgen.sequence.SequenceSpec;
import org.egov.idgen.template.CompiledTemplate;
import org.egov.idgen.template.CounterKey;

/** Resolve → key → allocate → render → record recent. */
@ApplicationScoped
public class IdService {

    public record Issued(List<String> ids, CompiledTemplate template, City city, ZonedDateTime issuedAt) {
        public String issuedAtText() {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(issuedAt.truncatedTo(ChronoUnit.SECONDS));
        }
    }

    private final TemplateRegistry templates;
    private final CityRegistry cities;
    private final BlockAllocator allocator;
    private final RecentIds recent;
    private final IdgenConfig config;

    @Inject
    public IdService(TemplateRegistry templates, CityRegistry cities, BlockAllocator allocator, RecentIds recent,
                     IdgenConfig config) {
        this.templates = templates;
        this.cities = cities;
        this.allocator = allocator;
        this.recent = recent;
        this.config = config;
    }

    /**
     * Issues {@code count} IDs. All or nothing: if allocation fails, nothing is returned and the numbers
     * already taken become a gap, never a duplicate.
     *
     * @throws org.egov.idgen.api.IdgenException UNKNOWN_TEMPLATE, UNKNOWN_CITY, COUNTER_NOT_CONFIGURED or
     *                                           SEQUENCE_UNAVAILABLE
     */
    public Issued issue(String templateId, String city, int count) {
        CompiledTemplate tpl = templates.get(templateId);
        City ten = cities.resolve(city);
        tpl.requireConfiguredCounter();
        ZonedDateTime now = ZonedDateTime.now(config.zone()); // read once: every ID shares date and period
        CounterKey key = tpl.counterKey(ten, now);
        long[] values = allocator.take(specFor(tpl, key), count);
        List<String> ids = new ArrayList<>(count);
        for (long v : values) {
            ids.add(tpl.render(ten, now, v));
        }
        Issued issued = new Issued(List.copyOf(ids), tpl, ten, now);
        recent.add(tpl.id(), ten.tenantId(), ten.code(), issued.ids(), issued.issuedAtText());
        return issued;
    }

    /** How to create the sequence behind {@code key} for {@code tpl}. */
    public SequenceSpec specFor(CompiledTemplate tpl, CounterKey key) {
        long start = tpl.start();
        return new SequenceSpec(SequenceNames.of(key), start,
                SequenceSpec.incrementFor(tpl.sequenceWidth(), start, config.blockSize()));
    }
}
