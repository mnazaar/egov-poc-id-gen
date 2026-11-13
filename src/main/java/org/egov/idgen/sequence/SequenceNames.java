package org.egov.idgen.sequence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.egov.idgen.template.CounterKey;

/**
 * Postgres sequence name for a counter key: {@code seq_} + key parts joined by {@code _}, lower case, anything
 * outside {@code [a-z0-9_]} replaced by {@code _}. Examples: {@code seq_pg_receipt_amr_2026_27},
 * {@code seq_pgr_complaint}, {@code seq_ws_amr_11_2026} (named counters drop their {@code SEQ_} prefix).
 *
 * <p>If two different keys ever map to the same name, they share one sequence: each still gets distinct
 * values, so the cost is shared numbering, never a duplicate.
 */
public final class SequenceNames {

    /** Postgres truncates identifiers longer than 63 bytes. */
    static final int MAX_LENGTH = 63;

    private SequenceNames() {}

    public static String of(CounterKey key) {
        List<String> parts = new ArrayList<>(4);
        String counter = key.counter();
        if (key.kind() == CounterKey.Kind.NAMED && counter.startsWith("SEQ_") && counter.length() > 4) {
            counter = counter.substring(4);
        }
        parts.add(counter);
        if (key.cityCode() != null) {
            parts.add(key.cityCode());
        }
        if (key.period() != null) {
            parts.add(key.period());
        }
        String name = "seq_" + sanitize(String.join("_", parts));
        if (name.length() > MAX_LENGTH) {
            String hash = sha256(key.toString()).substring(0, 8);
            name = name.substring(0, MAX_LENGTH - 9) + "_" + hash;
        }
        return name;
    }

    static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
