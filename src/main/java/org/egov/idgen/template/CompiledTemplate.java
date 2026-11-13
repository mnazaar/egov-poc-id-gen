package org.egov.idgen.template;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.egov.idgen.api.ErrorCode;
import org.egov.idgen.api.IdgenException;
import org.egov.idgen.config.City;
import org.jboss.logging.Logger;

/** A template parsed once at startup. Requests render its token list; there is no string replacement. */
public final class CompiledTemplate {

    private static final Logger LOG = Logger.getLogger(CompiledTemplate.class);

    private final String id;
    private final String name;
    private final String format;
    private final List<Token> tokens;
    private final Token.Sequence sequence;
    private final List<Token.Period> periods;
    private final boolean printsCity;
    /** First value issued; {@code null} when the named counter has no configuration. */
    private final Long start;
    private final Set<CounterKey> overflowWarned = ConcurrentHashMap.newKeySet();

    private CompiledTemplate(String id, String name, String format, List<Token> tokens, Long start) {
        this.id = id;
        this.name = name;
        this.format = format;
        this.tokens = tokens;
        this.start = start;
        Token.Sequence seq = null;
        List<Token.Period> ps = new ArrayList<>();
        boolean city = false;
        for (Token t : tokens) {
            switch (t) {
                case Token.Sequence s -> seq = s;
                case Token.Period p -> ps.add(p);
                case Token.CityCode c -> city = true;
                case Token.Literal l -> { }
            }
        }
        this.sequence = seq;
        this.periods = List.copyOf(ps);
        this.printsCity = city;
    }

    /**
     * Parses {@code format}. A named counter missing from {@code counters} is not an error here: the template
     * loads, and issuing from it fails with COUNTER_NOT_CONFIGURED.
     */
    public static CompiledTemplate compile(String id, String name, String format, Map<String, CounterSpec> counters) {
        List<Token> tokens = TemplateParser.parse(id, format);
        Token.Sequence seq = (Token.Sequence) tokens.stream()
                .filter(t -> t instanceof Token.Sequence).findFirst().orElseThrow();
        Long start;
        if (!seq.named()) {
            start = 1L;
        } else {
            CounterSpec spec = counters.get(seq.counter());
            start = spec == null ? null : spec.start();
        }
        return new CompiledTemplate(id, name, format, tokens, start);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String format() {
        return format;
    }

    public List<Token> tokens() {
        return tokens;
    }

    /** The counter this template draws from: its own ID for {@code [SEQ:n]}, else the named counter. */
    public String counterName() {
        return sequence.named() ? sequence.counter() : id;
    }

    public boolean printsCity() {
        return printsCity;
    }

    public boolean printsPeriod() {
        return !periods.isEmpty();
    }

    public boolean canIssue() {
        return start != null;
    }

    /** First value of every sequence this template uses. */
    public long start() {
        requireConfiguredCounter();
        return start;
    }

    /** @throws IdgenException COUNTER_NOT_CONFIGURED when the named counter has no {@code counters} entry */
    public void requireConfiguredCounter() {
        if (start == null) {
            throw new IdgenException(ErrorCode.COUNTER_NOT_CONFIGURED,
                    "Template " + id + " uses counter " + sequence.counter() + ", which has no configuration",
                    Map.of("templateId", id, "counter", sequence.counter()));
        }
    }

    /** The counter key for an ID issued now for {@code city} (ADR-003 scoping rule). */
    public CounterKey counterKey(City city, ZonedDateTime now) {
        String period = null;
        if (!periods.isEmpty()) {
            List<String> parts = new ArrayList<>(periods.size());
            for (Token.Period p : periods) {
                parts.add(p.render(now));
            }
            period = String.join("|", parts);
        }
        return new CounterKey(
                sequence.named() ? CounterKey.Kind.NAMED : CounterKey.Kind.TEMPLATE,
                counterName(),
                printsCity ? city.code() : null,
                period);
    }

    /** Renders one ID. {@code now} must already be in the service's zone (Asia/Kolkata). */
    public String render(City city, ZonedDateTime now, long value) {
        StringBuilder out = new StringBuilder(32);
        for (Token t : tokens) {
            switch (t) {
                case Token.Literal l -> out.append(l.text());
                case Token.CityCode c -> out.append(city.code());
                case Token.Period p -> out.append(p.render(now));
                case Token.Sequence s -> appendPadded(out, value, s.width(), city, now);
            }
        }
        return out.toString();
    }

    private void appendPadded(StringBuilder out, long value, int width, City city, ZonedDateTime now) {
        String digits = Long.toString(value);
        if (digits.length() > width) {
            CounterKey key = counterKey(city, now);
            if (overflowWarned.add(key)) {
                LOG.warnf("Counter %s passed its %d-digit width (value %d); IDs grow wider, never truncated",
                        key, width, value);
            }
        }
        out.repeat('0', Math.max(0, width - digits.length())).append(digits);
    }
}
