package org.egov.idgen.sequence;

import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.sqlclient.DatabaseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.egov.idgen.api.ErrorCode;
import org.egov.idgen.api.IdgenException;
import org.jboss.logging.Logger;

/**
 * The only code that talks to Postgres: {@code CREATE SEQUENCE}, {@code nextval}, and reading
 * {@code increment_by} from {@code pg_sequences}. No tables (ADR-001).
 *
 * <p>Calls block the calling worker thread; they are made only when an in-memory block runs out.
 */
@ApplicationScoped
public class SequenceStore {

    private static final Logger LOG = Logger.getLogger(SequenceStore.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_]{1,63}");
    /** Raised when another instance creates the same sequence at the same moment. */
    private static final Set<String> CONCURRENT_CREATE = Set.of("23505", "42P07");
    private static final String UNDEFINED_TABLE = "42P01";

    private final Pool pool;
    /** Sequences this instance has created or seen, with the increment Postgres reports. */
    private final Map<String, Long> increments = new ConcurrentHashMap<>();

    @Inject
    public SequenceStore(Pool pool) {
        this.pool = pool;
    }

    /**
     * Creates the sequence if needed and returns its real increment, read back from {@code pg_sequences}.
     * The block size must come from the sequence: an instance that assumed a different one would overlap.
     */
    public long ensure(SequenceSpec spec) {
        Long known = increments.get(spec.name());
        if (known != null) {
            return known;
        }
        String name = checkedName(spec.name());
        try {
            execute("CREATE SEQUENCE IF NOT EXISTS " + name
                    + " START WITH " + spec.start() + " MINVALUE " + Math.min(spec.start(), 1)
                    + " INCREMENT BY " + spec.increment(), null);
        } catch (IdgenException e) {
            if (!(e.getCause() instanceof DatabaseException db && CONCURRENT_CREATE.contains(db.getSqlState()))) {
                throw e;
            }
            LOG.debugf("Sequence %s was created concurrently by another instance", name);
        }
        Row row = execute("SELECT increment_by FROM pg_sequences"
                + " WHERE schemaname = current_schema() AND sequencename = $1", Tuple.of(name))
                .iterator().next();
        long increment = row.getLong(0);
        increments.put(name, increment);
        return increment;
    }

    /**
     * Takes {@code blocks} block starts in one round trip ({@code nextval} never returns a value twice, so
     * every block is this caller's alone). Recreates the sequence once if it was dropped.
     */
    public long[] nextBlocks(SequenceSpec spec, int blocks) {
        ensure(spec);
        try {
            return nextval(spec.name(), blocks);
        } catch (IdgenException e) {
            if (e.getCause() instanceof DatabaseException db && UNDEFINED_TABLE.equals(db.getSqlState())) {
                LOG.warnf("Sequence %s is missing; recreating it", spec.name());
                increments.remove(spec.name());
                ensure(spec);
                return nextval(spec.name(), blocks);
            }
            throw e;
        }
    }

    /** The increment this instance last read for {@code name}; call {@link #ensure} first. */
    public long increment(String name) {
        Long inc = increments.get(name);
        if (inc == null) {
            throw new IllegalStateException("Sequence " + name + " not ensured");
        }
        return inc;
    }

    private long[] nextval(String name, int blocks) {
        var rows = execute("SELECT nextval($1::regclass) FROM generate_series(1, $2)",
                Tuple.of(checkedName(name), blocks));
        long[] out = new long[blocks];
        int i = 0;
        for (Row r : rows) {
            out[i++] = r.getLong(0);
        }
        if (i != blocks) {
            throw new IdgenException(ErrorCode.SEQUENCE_UNAVAILABLE,
                    "Expected " + blocks + " values from " + name + ", got " + i);
        }
        return out;
    }

    private io.vertx.mutiny.sqlclient.RowSet<Row> execute(String sql, Tuple args) {
        try {
            return (args == null ? pool.query(sql).execute() : pool.preparedQuery(sql).execute(args))
                    .await().atMost(TIMEOUT);
        } catch (RuntimeException e) {
            throw new IdgenException(ErrorCode.SEQUENCE_UNAVAILABLE,
                    "The sequence store is unavailable; try again shortly", Map.of(), e);
        }
    }

    private static String checkedName(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe sequence name: " + name);
        }
        return name;
    }
}
