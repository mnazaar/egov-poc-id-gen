package org.egov.idgen.recent;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The last {@value #PER_KEY} IDs issued per (template, city) on this instance. In memory only: it resets on
 * restart and each instance sees only what it issued (ADR-001: a convenience, not an audit trail).
 */
@ApplicationScoped
public class RecentIds {

    public static final int PER_KEY = 50;

    /** @param order issue order on this instance; higher is newer */
    public record Entry(long order, String id, String templateId, String tenantId, String cityCode,
                        String issuedAt) {}

    private final Map<String, ArrayDeque<Entry>> byKey = new ConcurrentHashMap<>();
    private final AtomicLong order = new AtomicLong();

    public void add(String templateId, String tenantId, String cityCode, List<String> ids, String issuedAt) {
        ArrayDeque<Entry> deque = byKey.computeIfAbsent(templateId + "|" + cityCode, k -> new ArrayDeque<>());
        // Only the newest PER_KEY of a large bulk call can survive, so skip the rest.
        int from = Math.max(0, ids.size() - PER_KEY);
        synchronized (deque) {
            for (int i = from; i < ids.size(); i++) {
                deque.addFirst(new Entry(order.incrementAndGet(), ids.get(i), templateId, tenantId, cityCode,
                        issuedAt));
                if (deque.size() > PER_KEY) {
                    deque.removeLast();
                }
            }
        }
    }

    /** Newest first. {@code templateId} and {@code cityCode} are optional filters (null = any). */
    public List<Entry> recent(String templateId, String cityCode, int limit) {
        List<Entry> out = new ArrayList<>();
        for (ArrayDeque<Entry> deque : byKey.values()) {
            synchronized (deque) {
                for (Entry e : deque) {
                    if ((templateId == null || templateId.equals(e.templateId()))
                            && (cityCode == null || cityCode.equals(e.cityCode()))) {
                        out.add(e);
                    }
                }
            }
        }
        out.sort(Comparator.comparingLong(Entry::order).reversed());
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }
}
