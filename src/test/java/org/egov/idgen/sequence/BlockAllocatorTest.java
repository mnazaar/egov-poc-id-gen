package org.egov.idgen.sequence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.egov.idgen.api.ErrorCode;
import org.egov.idgen.api.IdgenException;
import org.junit.jupiter.api.Test;

/** BlockAllocator against an in-memory stand-in for Postgres sequences. */
class BlockAllocatorTest {

    /** Behaves like {@code CREATE SEQUENCE … START WITH s INCREMENT BY i} + {@code nextval}. */
    static class FakeStore extends SequenceStore {
        final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
        final Map<String, Long> increments = new ConcurrentHashMap<>();
        final AtomicInteger trips = new AtomicInteger();
        final AtomicBoolean down = new AtomicBoolean();

        FakeStore() {
            super(null);
        }

        @Override
        public long ensure(SequenceSpec spec) {
            failIfDown();
            increments.putIfAbsent(spec.name(), spec.increment());
            sequences.putIfAbsent(spec.name(), new AtomicLong(spec.start() - spec.increment()));
            return increments.get(spec.name());
        }

        @Override
        public long[] nextBlocks(SequenceSpec spec, int blocks) {
            ensure(spec);
            trips.incrementAndGet();
            long inc = increments.get(spec.name());
            long[] out = new long[blocks];
            for (int i = 0; i < blocks; i++) {
                out[i] = sequences.get(spec.name()).addAndGet(inc);
            }
            return out;
        }

        void failIfDown() {
            if (down.get()) {
                throw new IdgenException(ErrorCode.SEQUENCE_UNAVAILABLE, "down");
            }
        }
    }

    static final SequenceSpec PG = new SequenceSpec("seq_pg_receipt_amr_2026_27", 1, 100);
    static final SequenceSpec WS = new SequenceSpec("seq_ws_amr_11_2026", 1000, 10);

    @Test
    void firstValueIsStartAndValuesCountUp() {
        BlockAllocator a = new BlockAllocator(new FakeStore());
        assertArrayEquals(new long[] {1, 2, 3}, a.take(PG, 3));
        assertArrayEquals(new long[] {4}, a.take(PG, 1));
        assertArrayEquals(new long[] {1000, 1001}, a.take(WS, 2));
    }

    @Test
    void onlyOneDatabaseTripPerBlock() {
        FakeStore store = new FakeStore();
        BlockAllocator a = new BlockAllocator(store);
        for (int i = 0; i < 100; i++) {
            a.take(PG, 1);
        }
        assertEquals(1, store.trips.get());
        a.take(PG, 1);
        assertEquals(2, store.trips.get());
    }

    @Test
    void bulkAcrossManyBlocksFetchesThemInOneTrip() {
        FakeStore store = new FakeStore();
        BlockAllocator a = new BlockAllocator(store);
        a.take(WS, 5); // 5 left in the first block of 10
        long[] v = a.take(WS, 1000);
        assertEquals(1000, distinct(v).size());
        assertEquals(1005, v[0]);
        assertEquals(2, store.trips.get(), "the 995 still needed come from 100 blocks in one trip");
    }

    @Test
    void twoInstancesSharingASequenceNeverOverlap() {
        FakeStore postgres = new FakeStore();
        BlockAllocator one = new BlockAllocator(postgres);
        BlockAllocator two = new BlockAllocator(postgres);
        Set<Long> all = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            for (long v : one.take(PG, 3)) {
                assertEquals(true, all.add(v), "duplicate " + v);
            }
            for (long v : two.take(PG, 7)) {
                assertEquals(true, all.add(v), "duplicate " + v);
            }
        }
        assertEquals(5000, all.size());
    }

    @Test
    void parallelSingleAndBulkCallsNeverDuplicate() throws Exception {
        FakeStore postgres = new FakeStore();
        List<BlockAllocator> instances = List.of(new BlockAllocator(postgres), new BlockAllocator(postgres),
                new BlockAllocator(postgres));
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < 32; t++) {
            int worker = t;
            futures.add(pool.submit(() -> {
                List<Long> got = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    BlockAllocator a = instances.get((worker + i) % 3);
                    SequenceSpec spec = i % 2 == 0 ? PG : WS;
                    int count = i % 5 == 0 ? 1 + (i % 37) : 1;
                    for (long v : a.take(spec, count)) {
                        got.add(spec == PG ? v : -v); // keep the two sequences apart
                    }
                }
                return got;
            }));
        }
        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> f : futures) {
            all.addAll(f.get());
        }
        pool.shutdown();
        assertEquals(all.size(), new HashSet<>(all).size(), "duplicates found");
    }

    @Test
    void storeFailureSurfacesAsSequenceUnavailableAndRecoversLater() {
        FakeStore store = new FakeStore();
        BlockAllocator a = new BlockAllocator(store);
        store.down.set(true);
        IdgenException e = assertThrows(IdgenException.class, () -> a.take(PG, 1));
        assertEquals(ErrorCode.SEQUENCE_UNAVAILABLE, e.code());
        store.down.set(false);
        assertArrayEquals(new long[] {1}, a.take(PG, 1));
    }

    private static Set<Long> distinct(long[] v) {
        Set<Long> s = new HashSet<>();
        for (long x : v) {
            s.add(x);
        }
        return s;
    }
}
