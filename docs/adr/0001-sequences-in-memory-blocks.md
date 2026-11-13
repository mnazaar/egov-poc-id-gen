## ADR-001: Postgres sequences with in-memory blocks, no tables

**Decision:** each counter key gets one Postgres sequence with `INCREMENT BY 100`. A backend instance calls `nextval` once, takes the 100 numbers starting there, and issues them from memory. The service has no tables. Accepted, 13 Nov 2026.

**Context.** If every ID needs a DB round trip, busy keys become a bottleneck. The brief allows gaps but never a duplicate, and that is how Postgres sequences behave: `nextval` never blocks other callers, never rolls back, and never returns the same value twice. You can get gaps, but never duplicates.

**How it works**
1. **One sequence per counter key:** (template, city, period) for `[SEQ:n]`; (named counter, city, period) for `[SEQ_NAME:n]`. Templates with no `[CITY]` in the output (`PGR-`) get one global sequence. Names follow `seq_<counter>_<city>_<period>` in lower case, within Postgres's 63-character limit.
1. **Created at startup** for the current and next period, `START WITH` the configured `start` (1 by default). If another instance creates it at the same moment, catch `duplicate_object` and carry on.
1. **In memory:** `{next, end}` per key, guarded by a per-key lock. When `next == end`, one `nextval` returns `base`, and the block is `[base, base+100)`. Only one refill per key runs at a time; other callers wait for it.
1. **Bulk:** take what's left in the block. If more are needed, fetch several blocks in one trip with `SELECT nextval(…) FROM generate_series(1, k)`.
1. **Recent-IDs view:** an in-memory list of the last 50 IDs per (template, city) on each instance.

```sql
CREATE SEQUENCE IF NOT EXISTS seq_pg_receipt_amr_2026_27 START WITH 1    INCREMENT BY 100;
CREATE SEQUENCE IF NOT EXISTS seq_ws_amr                 START WITH 1000 INCREMENT BY 10;
CREATE SEQUENCE IF NOT EXISTS seq_pgr_complaint          START WITH 1    INCREMENT BY 100;

SELECT nextval('seq_pg_receipt_amr_2026_27');                       -- one block
SELECT nextval('seq_pg_receipt_amr_2026_27') FROM generate_series(1, 5);  -- five blocks, one trip
```

**Why it can't duplicate.** Each `nextval` returns a different starting number, `INCREMENT` apart, so blocks never overlap, whether across instances, restarts or crashes. The in-memory lock only protects a block that this one instance already owns.

**Why no tables at all.** The only state that has to be durable and shared is "which numbers are taken", and a sequence holds exactly that. Templates and cities are configuration loaded from `data/` at startup, not data the service owns. The recent-IDs view is a convenience and can live in memory. Any table would either duplicate what the sequence already guarantees (a counters table) or put a write back on every request (an issued-IDs log). If an audit trail is needed later, add an `issued_ids` table written in batches off the request path. That is an additive change and doesn't affect how numbers are allocated.

| Option | DB trips per ID | Tables | Verdict |
|---|---|---|---|
| Counters table, `UPDATE` per request | 1 | 1 | Hot row lock; most moving parts |
| Counters table, hi-lo block | 1/100 | 1 | Works, but rebuilds what a sequence already is |
| Sequence with `CACHE 100` | 1 | 0 | Caches inside each Postgres connection; the app still makes a trip per `nextval` |
| **Sequence ****`INCREMENT BY 100`**** + app block (chosen)** | 1/100 | 0 | Fewest trips, no locks in the DB, nothing to maintain |

**Consequences**
- **Gaps:** an instance restart or a new period leaves up to 99 unused numbers per key per instance. The brief allows this.
- **Order:** IDs are unique but not in strict time order across instances (A issues 1–100 while B issues 101–200).
- **Block size must come from the sequence:** at startup the app reads `increment_by` from `pg_sequences`, not from its own config. If an instance assumed 500 while the sequence increments by 100, its blocks would overlap.
- **Changing block size:** raising it (`ALTER SEQUENCE … INCREMENT BY 500`) is safe. Lowering it is not: first `setval` past the highest block already handed out, or blocks will overlap.
- **Narrow counters:** `SEQ_WS:4` has only 9,000 values, so it uses `INCREMENT BY 10`.
- **No issued-ID log:** there's no audit trail and no DB-level unique key as a backup. Uniqueness rests on the sequence plus correct rendering, and the load test is what proves it. The recent-IDs view is per instance and resets on restart. Audit history is out of scope.

**How we prove it:** run the concurrency test with `docker compose up --scale backend=3` behind Nginx, kill one backend container mid-run, and collect every ID the workers received. Zero duplicates is expected.

**With another day: block size that adapts to load.** A fixed 100 is too small for a hot key and too wasteful for a quiet one. The next step is to size each key's block per instance from its recent demand.
1. **Fixed small sequence step, variable fetch.** Keep every sequence at a small, fixed `INCREMENT BY` (e.g. 10) and vary only how many steps are fetched in one trip: `SELECT nextval(…) FROM generate_series(1, k)`. Because the sequence itself never changes, nothing can overlap. That's why this beats `ALTER SEQUENCE`, which is unsafe to lower.
1. **Pick k from demand.** Track an exponentially weighted rate of IDs/sec per key, and choose k so a block lasts about a target interval (e.g. 1–2 s), within `[1, max]`. A hot key like `PG_RECEIPT|BLR` grows to large blocks; a quiet key like `BPA_PERMIT|MDU` drops to one step of 10.
1. **Respect narrow counters.** Cap k from the remaining width space, so `SEQ_WS:4` never spends its 9,000 values on gaps.
1. **What it buys.** Hot keys make fewer DB trips; quiet keys and pods that scale down leave smaller gaps. Expose the current k and refill rate per key as metrics, so the tuning can be seen during the scaling test (ADR-002).
