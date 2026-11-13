# ID Generation Service — Architecture and Build Plan

2026-11-13 · Nazaar

A Quarkus + Postgres service that issues formatted government IDs (receipts, licences, permits) from configurable templates for 10 city tenants, with zero duplicates under parallel load. This document is the input for building it with Claude Code: the sections up to the ADRs are the specification, and **Build plan** gives the order of work with a done-check for each step.

## Context and goals

City services need human-readable IDs such as `PG-AMR-2026-27-000431`. Templates come from `data/templates.json` and tenants from `data/cities.csv`; both are provided and must not be edited.

**Hard rule:** an ID is never issued twice. Gaps in a sequence are acceptable.

**Goals**
- Issue one ID, or `count` IDs in one call, for a (template, city).
- Zero duplicates under parallel single and bulk calls, across several backend instances.
- Structured errors (never stack traces) for an unknown template, an unknown city, or a named counter with no configuration.
- Thin web UI: pick template + city, enter a count, issue, see the results and the recently issued IDs.
- `docker compose up` is the only command needed: build, start Postgres + backend + UI, load the data.
- A concurrency test that runs genuinely parallel workers against the running app.

**Non-goals:** authentication, editing templates at runtime, cancelling or reusing IDs, audit history, multi-region.

**Worked example:** `PG_RECEIPT` for `pb.amritsar` on 13 Nov 2026.

| Placeholder | Resolves to | Rule |
|---|---|---|
| `[CITY]` | `AMR` | city_code for tenant `pb.amritsar` |
| `[FY:yyyy-yy]` | `2026-27` | Nov 2026 is in the Apr 2026–Mar 2027 financial year |
| `[SEQ:6]` | `000001` | first value of the (PG_RECEIPT, AMR, 2026-27) counter, padded to 6 |

Result: `PG-AMR-2026-27-000001`, then `…-000002`. Ludhiana counts separately from `PG-LDH-2026-27-000001`.

## Templates and placeholder grammar

A template is parsed once at startup into a list of tokens (literal text and placeholders). Every request then renders that list: there is no string replacement at request time.

| Placeholder | Renders | Rule |
|---|---|---|
| `[CITY]` | `AMR` | `city_code` of the resolved tenant |
| `[FY:yyyy-yy]` | `2026-27` | Indian financial year, Apr–Mar: start year = year if month ≥ April, else year − 1 |
| `[FY:yy-yy]` | `26-27` | Same, two-digit years |
| `[CY:fmt]` | `2026`, `11-2026` | Current date formatted with `fmt` as a `java.time` pattern (`yyyy`, `MM-yyyy`) |
| `[SEQ:n]` | `000001` | Next value of the template's implicit counter, zero-padded to at least `n` digits |
| `[SEQ_NAME:n]` | `1000` | Next value of named counter `SEQ_NAME` (config in `counters`), padded the same way |

**Rendering rules**
- **Clock:** `Asia/Kolkata`, read once per request, so every ID in a bulk call shares the same date and period.
- **Padding is a minimum width.** A value wider than `n` is printed in full, never truncated (truncation would wrap around and duplicate). Log a warning the first time a key overflows.
- **Literal text** (`-`, `/`, prefixes) is copied unchanged.
- **Unknown placeholder syntax** fails startup with the template ID and position. A named counter with no `counters` entry is not a startup error: the template loads, and issuing from it returns 422 `COUNTER_NOT_CONFIGURED`.
- **`start`** is the first value issued for a named counter; implicit counters start at 1.

**The six provided templates**

| Template | Format | Counter scope (ADR-003) | First ID for Amritsar | Note |
|---|---|---|---|---|
| PG_RECEIPT | `PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]` | template + city + FY | `PG-AMR-2026-27-000001` | Resets each FY |
| TL_LICENSE | `TL/[CITY]/[CY:yyyy]/[SEQ:5]` | template + city + year | `TL/AMR/2026/00001` | `/` separators; resets each year |
| PGR_COMPLAINT | `PGR-[SEQ:8]` | template only (global) | `PGR-00000001` | No city in the output, so one counter for all cities |
| WS_CONNECTION | `WS-[CITY]-[SEQ_WS:4]-[CY:MM-yyyy]` | `SEQ_WS` + city + month | `WS-AMR-1000-11-2026` | Start 1000; width 4 grows past 9999 |
| BPA_PERMIT | `BPA-[CITY]-[FY:yy-yy]-[SEQ_BPA_PERMIT:6]` | `SEQ_BPA_PERMIT` + city + FY | `BPA-AMR-26-27-000001` | Short FY form |
| EST_ALLOTMENT | `EST-[CITY]-[SEQ_EST_ALLOT:5]` | — | 422 `COUNTER_NOT_CONFIGURED` | `SEQ_EST_ALLOT` has no `counters` entry |

Tenants: 10 cities across Punjab, Karnataka, Maharashtra and Tamil Nadu. A request may name a city by tenant ID (`pb.amritsar`) or city code (`AMR`).

## Acceptance criteria

The build is done when every box below can be ticked from a fresh clone. Reviewers will run `docker compose up` and send parallel requests to the endpoints.

**Functional**
- `POST /api/ids` returns one ID for every provided template × city except EST_ALLOTMENT.
- `POST /api/ids/bulk` returns exactly `count` distinct IDs, in one response.
- `city` accepts `pb.amritsar`, `AMR` and `amr`.
- Unknown template → 404 `UNKNOWN_TEMPLATE`; unknown city → 404 `UNKNOWN_CITY`; EST_ALLOTMENT → 422 `COUNTER_NOT_CONFIGURED`; `count` outside 1–1000 → 400 `INVALID_COUNT`. Every error uses the same JSON shape, with no stack traces.
- The UI issues single and bulk IDs and shows a recent-IDs list.

**Concurrency and operations**
- The load test (50 parallel workers, mixed single + bulk, all templates and cities, 3 backend instances) reports 0 duplicates.
- A backend instance restarted mid-test still gives 0 duplicates.
- `docker compose up` from a fresh clone is the only command; the README gives the URL.
- `data/` files are byte-for-byte unchanged.

**Documents in the repo**
- `spec.md`, `plan.md`, `README.md`, `docs/ai-log.md`, and `docs/adr/0001–0005`.

## Decisions on the brief's open points

The brief is deliberately underspecified. Each decision below follows one principle: **when in doubt, choose the option that cannot produce a duplicate.** These go into `spec.md`, and the ones that need confirming are in the questions table at the end.

| # | Open point | Decision | Why |
|---|---|---|---|
| 1 | `PGR-[SEQ:8]` has no `[CITY]`, but `[SEQ]` is scoped per (template, city) | One global counter for templates whose output has no `[CITY]` | Per city, every city would emit `PGR-00000001` |
| 2 | `SEQ_EST_ALLOT` has no `counters` entry | 422 `COUNTER_NOT_CONFIGURED`; never auto-create it | Required by the brief |
| 3 | `[SEQ_WS:4]` starts at 1000, so it holds only 9,000 values | `n` is a minimum width; the ID grows; never truncate | Truncation wraps around into duplicates |
| 4 | Do counters reset with the FY / year / month? | Yes, but only when that period is printed in the ID: a new period means a new sequence | `TL/AMR/2026/00001` and `TL/AMR/2027/00001` differ; `PGR-` has no period, so it never resets |
| 5 | Which clock decides FY/CY? | Asia/Kolkata, read once per request | 31 Mar 20:00 UTC is already 1 Apr IST |
| 6 | Meaning of `start` | The first issued value equals `start` | Most natural reading |
| 7 | `city` = tenant ID or city code | Accept both, case-insensitive; respond with the canonical values | The brief allows either |
| 8 | Bulk limits | `count` 1–1000; all-or-nothing response | Bounds the response size; on failure, gaps are acceptable |
| 9 | Multiple instances and restarts | Postgres sequences own the high-water mark; instances cache only blocks they took via `nextval` (ADR-001) | Counters held only in memory would duplicate across instances |
| 10 | A named counter used by two templates | One shared sequence per (counter, city, period) | Matches "named counter scoped per city" |

**Out of scope:** authentication, editing templates at runtime, cancelling or reusing IDs, audit history, multi-region.

## Architecture

The whole problem comes down to one atomic step: a Postgres sequence hands out each block of 100 exactly once, and the app issues from that block in memory.

**The core of the solution** is one Postgres sequence per counter key, incrementing by 100. Each instance calls nextval only when its in-memory block runs out (ADR-001):

```sql
CREATE SEQUENCE IF NOT EXISTS seq_pg_receipt_amr_2026_27 START WITH 1 INCREMENT BY 100;  -- once, at startup
SELECT nextval('seq_pg_receipt_amr_2026_27');  -- returns the block start: 1, then 101, 201…
```

`nextval` is atomic and never blocks other callers, so every instance gets a disjoint block `[base, base+100)`. Different keys never contend with each other. This is the classic hi-lo pattern used for voucher numbers in core banking, here built on a plain Postgres sequence.
- **One sequence p****er key** = scope + period, e.g. `seq_pg_receipt_amr_``2026``_``27`, `seq_pgr_complaint` (global), `seq_ws_amr`.
- **Not recommended:** in-process locks or `SELECT max()+1` (unsafe with more than one replica), or Redis INCR (adds a second store for no gain at this scale).
- **Stack:** Quarkus (JVM mode) + Postgres 16. The static UI is served by the same app (ADR-002).
- **Data model:** no tables. Postgres holds only sequences. Config is loaded from `data/` into memory, and the recent-IDs view is an in-memory list per instance.
- **Errors:** one shape, e.g. `{"error":{"code":"UNKNOWN_CITY","message":"…","details":{…}}}`, with 404 for an unknown template/city, 422 for an unconfigured counter, and 400 for a bad `count`.

**Runtime topology (****`docker-compose.yml`****)**

| Service | Image | Exposed | Notes |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | internal | Healthcheck `pg_isready`; holds only sequences |
| `backend` | built from `Dockerfile` (tag `<dockerhub-user>/idgen:1.0.0`) | internal :8080 | `deploy.replicas: 3`; `depends_on: postgres (service_healthy)`; mounts `./data:/data:ro` |
| `nginx` | `nginx:alpine` | **host :8080** | Round-robins to `backend:8080`; the only public port, so reviewers' requests spread over 3 instances |
| `loadtest` | `python:3.12-slim` | — | `profiles: [test]`; runs `loadtest/loadtest.py` against `nginx` |

## API contract

All endpoints are JSON under `/api`. The UI is served from `/`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/ids` | Issue one ID |
| POST | `/api/ids/bulk` | Issue `count` IDs |
| GET | `/api/ids/recent?templateId=&city=&limit=50` | Recently issued IDs on the instance that serves the request |
| GET | `/api/templates` | Templates for the UI dropdown, including whether each can issue |
| GET | `/api/cities` | Cities for the UI dropdown |
| GET | `/q/health/ready` | Readiness: config loaded and Postgres reachable |

**Issue one**

```http
POST /api/ids
{ "templateId": "PG_RECEIPT", "city": "pb.amritsar" }

200 OK
{ "id": "PG-AMR-2026-27-000001", "templateId": "PG_RECEIPT",
  "tenantId": "pb.amritsar", "cityCode": "AMR", "issuedAt": "2026-11-13T05:12:04+05:30" }
```

**Issue bulk**

```http
POST /api/ids/bulk
{ "templateId": "TL_LICENSE", "city": "LDH", "count": 3 }

200 OK
{ "ids": ["TL/LDH/2026/00001", "TL/LDH/2026/00002", "TL/LDH/2026/00003"],
  "count": 3, "templateId": "TL_LICENSE", "tenantId": "pb.ludhiana", "cityCode": "LDH",
  "issuedAt": "2026-11-13T05:12:09+05:30" }
```

**Errors:** one shape for every failure.

```json
{ "error": { "code": "COUNTER_NOT_CONFIGURED",
             "message": "Template EST_ALLOTMENT uses counter SEQ_EST_ALLOT, which has no configuration",
             "details": { "templateId": "EST_ALLOTMENT", "counter": "SEQ_EST_ALLOT" } } }
```

| Code | HTTP | When |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Body missing, malformed JSON, or a missing `templateId` / `city` |
| `INVALID_COUNT` | 400 | `count` not an integer in 1–1000 |
| `UNKNOWN_TEMPLATE` | 404 | `templateId` not in `templates.json` |
| `UNKNOWN_CITY` | 404 | `city` matches no tenant ID or city code |
| `COUNTER_NOT_CONFIGURED` | 422 | Template uses a named counter with no `counters` entry |
| `SEQUENCE_UNAVAILABLE` | 503 | Postgres unreachable while refilling a block |
| `INTERNAL_ERROR` | 500 | Anything else; logged with a correlation ID, which is returned in `details` |

## Code structure

Java 25, Quarkus 3.x, Maven wrapper, one module. Package `org.egov.idgen`:

| Package | Classes | Responsibility |
|---|---|---|
| `config` | `IdgenConfig` (`@ConfigMapping`), `DataLoader` (`@Startup`), `TemplateRegistry`, `CityRegistry` | Read `data/` once; validate; fail startup with file + line on bad input |
| `template` | `Token` (sealed: `Literal`, `City`, `FinancialYear`, `CalendarDate`, `Sequence`), `TemplateParser`, `CompiledTemplate`, `CounterKey` | Parse formats into tokens; derive the counter key; render one ID from (tokens, city, clock, value) |
| `sequence` | `SequenceNames`, `SequenceStore`, `BlockAllocator`, `Block` | Create sequences, call `nextval`, hand out values from in-memory blocks |
| `issue` | `IdService` | Resolve → key → allocate → render → record recent |
| `recent` | `RecentIds` | Bounded in-memory list (50) per (template, city) |
| `api` | `IdResource`, `MetaResource`, request/response `record`s, `ErrorCode`, `IdgenException`, `ErrorMapper` | REST endpoints; one `ExceptionMapper` that produces the error shape |

**Issue flow (****`IdService.issue`****)**

```java
Issued issue(String templateId, String city, int count) {
    var tpl  = templates.get(templateId);            // UNKNOWN_TEMPLATE
    var ten  = cities.resolve(city);                 // UNKNOWN_CITY (tenant id or code, case-insensitive)
    tpl.requireConfiguredCounter();                  // COUNTER_NOT_CONFIGURED
    var now  = ZonedDateTime.now(config.zone());     // read once
    var key  = tpl.counterKey(ten, now);             // ADR-003 scoping rule
    long[] v = allocator.take(key, count);           // ADR-001
    var ids  = Arrays.stream(v).mapToObj(x -> tpl.render(ten, now, x)).toList();
    recent.add(tpl.id(), ten.code(), ids);
    return new Issued(ids, tpl, ten, now);
}
```

**Block allocator (****`BlockAllocator.take`****)**

```java
// one Block per counter key per instance; the lock is per key, never global
long[] take(CounterKey key, int count) {
    var block = blocks.computeIfAbsent(key, k -> new Block());
    synchronized (block) {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            if (block.next == block.end) {
                long base = store.nextval(key);        // creates the sequence on first use
                block.next = base;
                block.end  = base + store.increment(key); // read from pg_sequences, never from config
            }
            out[i] = block.next++;
        }
        return out;
    }
}
```

A bulk request that crosses a block boundary refills inside the loop. The cost is one `nextval` per 100 values; batching these into one `generate_series` call can come later.

**Sequence store**
- **Name:** `seq_` + key parts joined by `_`, lowercased, with anything outside `[a-z0-9_]` replaced by `_`. If it would exceed 63 characters, truncate and append an 8-character hash.
- **Create:** `CREATE SEQUENCE IF NOT EXISTS <name> START WITH <start> INCREMENT BY <block>`. Pre-create at startup for every key's current period, and create on first use otherwise. Treat SQLSTATE `23505` / `42P07` from a concurrent create as success.
- **Increment:** read `increment_by` from `pg_sequences` after create and cache it per key.
- **Client:** reactive PG client or Agroal JDBC; only `CREATE SEQUENCE`, `nextval` and the `pg_sequences` read. No ORM.

**Tests**
- **Unit (****`TemplateParserTest`****, ****`RenderTest`****):** all six templates; FY on 31 Mar vs 1 Apr; padding growth (`SEQ_WS` value 10000); PGR key has no city; EST rejected.
- **API (****`IdResourceTest`****, RestAssured + Quarkus Dev Services Postgres):** each error code and its shape; tenant ID / code / lower-case all resolve.
- **Concurrency (****`loadtest/loadtest.py`****):** `multiprocessing.Pool(50)` with real OS processes; each worker loops over random (template, city, single or bulk 1–50) for N seconds; all IDs are collected and the test fails if `len(set(ids)) != len(ids)`. It prints totals, duplicates and requests/sec. Run with `docker compose --profile test run --rm loadtest`.
- **Throughput (`loadtest/idgen.jmx`, JMeter):** a thread group of 50 users for 60 s against Nginx, mixing `POST /api/ids` and `POST /api/ids/bulk` over random templates and cities from a CSV data set. Assertions: HTTP 200 and the `id` matches its template's pattern. Run headless with `jmeter -n -t loadtest/idgen.jmx -l loadtest/out/results.jtl -e -o loadtest/out/report`; the HTML report gives throughput, p95/p99 latency and error rate. JMeter measures speed; `loadtest.py` stays the proof of zero duplicates, because it collects every ID.

## Architecture decision records

Each decision lives in its own file under [`docs/adr/`](adr/):

- [ADR-001: Postgres sequences with in-memory blocks, no tables](adr/0001-sequences-in-memory-blocks.md)
- [ADR-002: Build the service on Quarkus (JVM now, native later)](adr/0002-quarkus.md)
- [ADR-003: Counter scoping and multi-tenancy](adr/0003-counter-scoping-multi-tenancy.md)
- [ADR-004: Nginx as the load balancer, not an API gateway](adr/0004-nginx-over-api-gateway.md)
- [ADR-005: Templates and cities are loaded once at startup; changes need a restart](adr/0005-config-loaded-at-startup.md)

## Build plan for Claude Code

Start 5:00 IST, submit by 8:00 IST. Code first; the documents are written from what was actually built. Save this document in the repo as `docs/architecture.md`, and point `CLAUDE.md` at it so every session starts with the same spec.

**Rules for Claude Code (put these in ****`CLAUDE.md`****)**
- Java 25, Quarkus 3.x, Maven wrapper. Extensions: `rest`, `rest-jackson`, `reactive-pg-client`, `smallrye-health`, `hibernate-validator`. No ORM, no tables.
- Never modify anything in `data/`.
- Follow the API contract and error codes in this document exactly; DTOs are Java `record`s.
- Locks are per counter key, never global. Never call `nextval` per ID; always go through `BlockAllocator`. If endpoints run on virtual threads, use `ReentrantLock` instead of `synchronized`.
- Every step ends with `./mvnw test` green and `docker compose up --build` working. Commit after each step with a clear message.
- When unsure, choose the option that cannot produce a duplicate, and add a line to `docs/NOTES.md`.

**Steps**
1. **5:00–5:15 Skeleton.** Create the public GitHub repo, `.gitignore`, `docs/NOTES.md` and `CLAUDE.md`. Scaffold Quarkus, write the multi-stage `Dockerfile`, and write `docker-compose.yml` with Postgres + 3 backend replicas + Nginx. Add a `GET /api/ping`.
1. *Done when:* `docker compose up` serves `/api/ping` through Nginx on :8080 and `/q/health/ready` is UP.
- **5:15–5:45 Config + templates.** `DataLoader`, `TemplateRegistry`, `CityRegistry`, `TemplateParser`, `CompiledTemplate.render`, `CounterKey`, plus unit tests.
- *Done when:* render tests pass for all six templates, the FY boundary, padding growth, PGR global key and EST rejection; `GET /api/templates` and `GET /api/cities` return data.
- **5:45–6:15 Allocation + API.** `SequenceStore`, `BlockAllocator`, `IdService`, `IdResource`, `ErrorMapper`, `RecentIds`, plus API tests.
- *Done when:* single + bulk work for every template × city; every error code returns the documented shape.
- **6:15–6:40 Concurrency test.** `loadtest/loadtest.py` + compose `loadtest` profile, plus the JMeter plan `loadtest/idgen.jmx` for throughput and latency.
- *Done when:* 0 duplicates against 3 replicas, and again with `docker compose restart backend` mid-run. Save the output for the README.
- **6:40–7:00 UI.** `index.html`: template + city dropdowns from the meta endpoints, a count field, issue, a results list, and a recent-IDs panel. **Code freeze at 7:00:** tag v1.0.0 and start the Docker Hub push in a second terminal.
- **7:00–7:40 Documents from the code.** `spec.md` (problem, decisions table, out of scope), `docs/adr/0001–0005`, `plan.md` (milestones as they went, data model = sequences only, API from the real endpoints, test strategy, another day), `README.md` (`docker compose up`, the URL, test command, prebuilt image), `docs/ai-log.md` (kept up to date after every step; at the end add the exported session + 3–5 sentences from `docs/NOTES.md`).
- **7:40–8:00 Verify and submit.** `git clone` into a new folder, `docker compose up`, run the load test, pull and run the Docker Hub image, then reply to the email with the repo link and the questions table.

**If time runs short, cut in this order:** the JMeter plan, then the 3 replicas + Nginx (run against one instance and list multi-instance under "another day"), then the recent-IDs panel, then the API tests. Hold the 7:00 code freeze. Never cut the load test or the fresh-clone check.

## Repo and Docker image

One public GitHub repo holds the code, tests and documents. The image is also pushed to Docker Hub, but `docker compose up` always builds from source, so the reviewer's path never depends on the registry.

```text
idgen/
├── docker-compose.yml        # postgres + backend (x3 option) + nginx
├── Dockerfile                # multi-stage: Maven build → JVM runtime
├── CLAUDE.md                 # rules for Claude Code; points to docs/architecture.md
├── docs/NOTES.md             # running assumptions + AI mistakes (feeds spec.md, docs/ai-log.md)
├── docs/architecture.md      # this document
├── nginx/nginx.conf          # round-robin to backend:8080
├── README.md                 # compose up, URL, test command, Docker Hub image
├── spec.md  plan.md  docs/ai-log.md
├── docs/adr/0001-sequences-in-memory-blocks.md
├── docs/adr/0002-quarkus.md
├── docs/adr/0003-counter-scoping-multi-tenancy.md
├── docs/adr/0004-nginx-over-api-gateway.md
├── docs/adr/0005-config-loaded-at-startup.md
├── data/                     # templates.json, cities.csv — unchanged
├── src/main/java/…           # config, renderer, allocator, REST, errors
├── src/main/resources/application.yaml         # settings + data file paths
├── src/main/resources/META-INF/resources/index.html   # UI
├── src/test/java/…           # renderer unit tests
└── loadtest/                 # loadtest.py (duplicate check) + idgen.jmx (JMeter throughput)
```
- **Before 5:00 (5 minutes):** create the Docker Hub repository (e.g. `<dockerhub-user>/idgen`) and an access token, then `docker login`. Put nothing secret in the repo.
- **Commit at the end of every step** with a clear message. Combined with the code-first order, the git log becomes evidence of how you scoped the time.
- **Compose:** give the backend both `build: .` and `image: <dockerhub-user>/idgen:1.0.0`. A clean checkout builds locally, and the built image carries the published name.
- **Push at the 7:00 freeze:** `docker buildx build --platform linux/amd64,linux/arm64 -t <dockerhub-user>/idgen:1.0.0 -t <dockerhub-user>/idgen:latest --push .`. Build for both architectures, because reviewers on Apple Silicon Macs need arm64. The arm64 leg runs under emulation and can take several minutes, so it runs while you write the docs.
- **README:** add a "Run the prebuilt image" section with `docker pull` and a `docker run` against the compose Postgres.
- **Another day:** a GitHub Actions workflow (`docker/build-push-action`) that pushes on every tag. Today a manual push avoids debugging CI against the clock.

## Open questions and POC assumptions

The POC is built on the assumptions below rather than waiting for answers. They go into `spec.md`, and the table goes in the submission email as "question → what the POC assumes → easy to change if you prefer otherwise".

| # | Question | What the POC assumes |
|---|---|---|
| 1 | The email asks for a spec + 2–3 ADRs + one API prototype; BRIEF.md asks for a full system. Which governs? | Built the full system from BRIEF.md. ADRs are in `docs/adr/`, and the issue endpoint is the "one API". |
| 2 | `PGR-[SEQ:8]` has no city in its output. Should its counter be global to keep IDs unique across cities? | One global sequence for PGR_COMPLAINT, which never resets. |
| 3 | Should counters reset when the FY / year / month in the ID changes? | Yes, but only when that period appears in the ID: a new period means a new sequence starting again from `start`. |
| 4 | When a sequence passes its padding width (e.g. `SEQ_WS:4` past 9999), should the ID grow or fail? | The ID grows wider and is never truncated. A warning is logged. |
| 5 | Is IST the reference timezone for FY and date placeholders? | Asia/Kolkata, read once per request (bulk included). |
| 6 | Is there a maximum bulk `count`, and what load will the review use? | `count` 1–1000. Tested with 50 workers against 3 backend instances. |
| 7 | IDs come from per-instance blocks of 100. Is it acceptable that they aren't in strict time order and may have gaps of up to 99 after a restart? | Yes. The brief allows gaps; only uniqueness is guaranteed (ADR-001). |
| 8 | Does the recently-issued view need to survive restarts or show all instances? | No. It's an in-memory list per instance, with no tables (ADR-001). |
