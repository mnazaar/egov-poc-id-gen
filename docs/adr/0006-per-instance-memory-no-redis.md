## ADR-006: Number blocks and recent IDs stay in each instance's memory (no Redis)

**Decision:** each backend instance keeps two things in its own memory: its current block of numbers per counter (ADR-001) and its last 50 issued IDs per (template, city). Neither goes through Redis or any other central cache. We accept that IDs are not issued in strict sequence across instances, and that the recent-IDs view shows only what the instance answering has issued. Accepted, 13 Nov 2026.

**Context.** Nginx spreads requests round-robin over 3 instances (ADR-004), and each instance issues from its own block. Four calls in a row for Amritsar receipts return:

```
PG-AMR-2026-27-000001   instance A (block 1–100)
PG-AMR-2026-27-000101   instance B (block 101–200)
PG-AMR-2026-27-000201   instance C (block 201–300)
PG-AMR-2026-27-000002   instance A again
```

The brief asks for uniqueness, not order: "an occasional gap in the sequence is acceptable; a duplicate is never acceptable."

**Why the numbers stay local, not in Redis.** A shared counter (Redis `INCR`) would put a network round trip on every ID, which is exactly what the in-memory blocks avoid: today an instance goes to Postgres once per 100 IDs (once per 10 for narrow counters) and issues the rest from memory. Redis would also add a second stateful store to run, and one whose durability settings decide whether a counter can go backwards after a failure, which would mean duplicates. Measured with local blocks: 817 requests/s at p95 56 ms through Nginx (README, JMeter).

**Why the recent view stays local for now.** A shared view needs a write to a shared store on every issue (latency again) or an asynchronous pipeline (more moving parts). The brief asks for "a simple view of recently issued IDs", and it is a convenience, not an audit trail (ADR-001).

| Option for the recent view | Shared across instances | Cost on the issue path | Verdict |
|---|---|---|---|
| Per-instance memory (chosen) | No | None | Simplest; list depends on which instance answers |
| Redis list per (template, city), `LPUSH` + `LTRIM 50` | Yes | A network write per request, unless sent asynchronously | Best "another day" option, off the request path |
| Postgres `issued_ids` table | Yes, and durable | A write per request, unless batched | Becomes the audit trail if one is ever needed (ADR-001) |
| Nginx sticky sessions (`ip_hash`) | No, but one browser always sees the same instance | None | Hides the effect for a single user; skews load across instances |

**Consequences**
- **IDs jump by instance:** each instance counts up inside its own block, so IDs from different instances interleave (`000001`, `000101`, `000201`, `000002`). Sorting IDs does not give issue order; `issuedAt` does.
- **Gaps:** a restart leaves up to one block (100, or 10) unused per counter per instance (ADR-001). Uniqueness is unaffected.
- **Recent view differs per request:** refreshing can show another instance's list, and a restart empties that instance's list. The UI labels it "recently issued on this instance".
- **No extra infrastructure:** nothing beyond Postgres to run, and no dependency on Redis being up for issuing.

**With another day: a shared recent view without slowing issuing.**
- After an ID batch is issued, publish it **asynchronously** (not awaited by the request) to a Redis list per (template, city) with `LPUSH` + `LTRIM 0 49`, or to a Redis stream. The UI reads the recent view from Redis.
- Issue latency stays the same because the request doesn't wait for Redis; if Redis is down, the recent view goes stale but issuing carries on.
- Numbering stays on local blocks from Postgres sequences. Redis is never on the path that decides a number.
