package org.egov.idgen.sequence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hands out sequence values from in-memory blocks (ADR-001). Each {@code nextval} gives this instance the
 * block {@code [base, base + increment)} alone, so values never repeat across instances, restarts or crashes.
 *
 * <p>One lock per sequence, never a global one: a busy counter never slows another. A {@link ReentrantLock}
 * rather than {@code synchronized}, so it stays correct if endpoints move to virtual threads.
 */
@ApplicationScoped
public class BlockAllocator {

    /** Upper bound on blocks fetched in one round trip. */
    static final int MAX_BLOCKS_PER_TRIP = 1000;

    private final SequenceStore store;
    private final Map<String, Block> blocks = new ConcurrentHashMap<>();

    @Inject
    public BlockAllocator(SequenceStore store) {
        this.store = store;
    }

    /**
     * Returns {@code count} values from the sequence, all distinct and never issued before. A bulk request
     * that runs past the current block fetches the blocks it still needs in one round trip.
     */
    public long[] take(SequenceSpec spec, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        Block block = blocks.computeIfAbsent(spec.name(), n -> new Block());
        block.lock.lock();
        try {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                if (block.next >= block.end) {
                    refill(block, spec, count - i);
                }
                out[i] = block.next++;
            }
            return out;
        } finally {
            block.lock.unlock();
        }
    }

    private void refill(Block block, SequenceSpec spec, int stillNeeded) {
        if (block.pending.isEmpty()) {
            long increment = store.ensure(spec);
            int trips = (int) Math.min(MAX_BLOCKS_PER_TRIP, Math.max(1, ceilDiv(stillNeeded, increment)));
            for (long base : store.nextBlocks(spec, trips)) {
                block.pending.add(base);
            }
            block.increment = increment;
        }
        long base = block.pending.poll();
        block.next = base;
        block.end = base + block.increment;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /** One sequence's state on this instance. Guarded by {@link #lock}. */
    private static final class Block {
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        long next;
        long end;
        long increment;
    }
}
