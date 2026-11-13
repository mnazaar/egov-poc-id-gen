package org.egov.idgen.sequence;

/**
 * How to create a sequence if it doesn't exist yet.
 *
 * @param name      Postgres sequence name (see {@link SequenceNames})
 * @param start     first value issued ({@code START WITH})
 * @param increment preferred block size for a new sequence ({@code INCREMENT BY}); allocation always uses
 *                  the increment Postgres reports, never this value
 */
public record SequenceSpec(String name, long start, long increment) {

    /** Counters with fewer values than this before outgrowing their width get small blocks (ADR-001). */
    static final long NARROW_CAPACITY = 10_000;
    static final long NARROW_INCREMENT = 10;

    /**
     * Block size for a counter: {@code blockSize}, or 10 when the counter has fewer than 10,000 values within
     * its printed width (e.g. {@code SEQ_WS:4} starting at 1000 has 9,000), so gaps don't waste them.
     */
    public static long incrementFor(int width, long start, long blockSize) {
        double capacity = Math.pow(10, width) - start;
        return capacity < NARROW_CAPACITY ? Math.min(NARROW_INCREMENT, blockSize) : blockSize;
    }
}
