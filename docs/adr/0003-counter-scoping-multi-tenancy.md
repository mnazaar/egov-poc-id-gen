## ADR-003: Counter scoping and multi-tenancy

**Decision:** tenancy is a namespace in the sequence name, inside one shared Postgres database. A counter is scoped by exactly the variable parts that appear in the ID it produces, so `PGR-` gets one global counter while every other template gets one per city. Accepted, 13 Nov 2026.

**Context.** The brief scopes `[SEQ:n]` per (template, city) and named counters per city. Applied literally, that gives a duplicate for `PGR-[SEQ:8]`, whose output has no city. The email also asks how the design handles multi-tenancy.

**The scoping rule:** a part of the key that isn't printed in the ID can't be used to tell IDs apart, so it must not scope the counter.

| Template shape | Counter key | Example sequence |
|---|---|---|
| `[SEQ:n]` with `[CITY]` | template + city (+ period if printed) | `seq_pg_receipt_amr_2026_27` |
| `[SEQ:n]` without `[CITY]` | template only (+ period if printed) | `seq_pgr_complaint` |
| `[SEQ_NAME:n]` with `[CITY]` | named counter + city (+ period if printed); shared by every template that uses it | `seq_ws_amr` |
| `[SEQ_NAME:n]` with no `counters` entry | rejected: 422 `COUNTER_NOT_CONFIGURED` | `EST_ALLOTMENT` |

**Tenant resolution.** `city` accepts the tenant ID (`pb.amritsar`) or the city code (`AMR`), case-insensitively, against `cities.csv`. An unknown value returns 404 `UNKNOWN_CITY`. The response echoes the canonical `tenantId` and `cityCode`.

**Isolation options**

| Option | Isolation | Cost | Verdict |
|---|---|---|---|
| **Shared DB, sequences namespaced by name (chosen)** | Independent numbering per tenant; no shared lock | None: a sequence holds no tenant data | Right for 10 cities and counters-only state |
| Schema per tenant | Separate namespaces | Onboarding and migrations per tenant | Adds work with no benefit here |
| Database per tenant/state | Hard separation, separate failures | Connection pools and operations × N | Only if a state requires data residency |

**Consequences**
- **No noisy neighbours:** each tenant has its own sequences and its own in-memory lock per key, so a busy city never slows another one.
- **Onboarding a city** = a row in `cities.csv`. Its sequences are created at the next startup, or on first use.
- **Shared fate:** one Postgres serves every tenant, so an outage hits all of them. That's acceptable for the POC; split by state if residency or blast radius matters.
- **No caller authentication:** any caller can issue IDs for any tenant. Another day: take the tenant from a verified token claim, not the request body.

**Configuration: data files as-is, settings in ****`application.yaml`****.** `cities.csv` and `templates.json` stay in `data/` exactly as provided and are read at startup. They are not copied into a table or into YAML.

| Option | Verdict |
|---|---|
| **Read ****`data/`**** files at startup (chosen)** | Matches the brief ("use the provided data files as-is", "load both files at startup"). One source of truth. |
| Copy into `application.yaml` | A second copy of the data: a reviewer who edits `templates.json` would see no effect. |
| Load into a `cities` / `templates` table | Brings back a table for data the service doesn't own (ADR-001), plus a loader to maintain. |

`application.yaml` holds only the service's own settings, including where to find the files:

```yaml
idgen:
  data:
    templates-path: /data/templates.json
    cities-path: /data/cities.csv
  block-size: 100
  bulk-max: 1000
  zone: Asia/Kolkata
```
- **Compose** mounts `./data:/data:ro`, so a changed file takes effect on restart, with no rebuild.
- **The image** also copies `data/` in as a default, so the Docker Hub image runs on its own.
- **Invalid or missing files** stop startup with a clear message naming the file and the line, not a stack trace.
- **Another day:** if templates and cities must change at runtime, they belong in a master-data service with an admin API (DIGIT's MDMS plays this role), not in a table inside the ID generator.
