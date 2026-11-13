# Spec: Templated ID Generation Service

## The problem

City services need human-readable IDs such as `PG-AMR-2026-27-000431` (payment receipts, trade licences, complaints, water connections, building permits). The service issues them from templates in `data/templates.json` for the 10 tenants in `data/cities.csv`.

- **Issue one ID**, or **`count` IDs in one call**, for a template and a city (named by tenant ID or city code).
- **Never issue the same ID twice**, under parallel single and bulk requests, across several backend instances and restarts. Gaps in a sequence are acceptable.
- **Fail clearly** for an unknown template, an unknown city, or a template whose named counter has no configuration: one JSON error shape, never a stack trace.
- **A thin web UI**: pick template and city, enter a count, issue, see the IDs and the recently issued ones.
- **`docker compose up` is the only command**, from a fresh clone. The data files are used exactly as delivered.

## Guiding rule

The brief is deliberately underspecified. Every decision below follows one rule: **when in doubt, choose the option that cannot produce a duplicate.** Where that still left a choice, the simpler option won.

## Questions for the reviewers

The POC is built on these assumptions rather than waiting for answers; each is easy to change. The first three matter most, because each changes which IDs are issued.

| # | Question | What the POC assumes |
|---|---|---|
| 1 | `PGR-[SEQ:8]` prints no city, but `[SEQ:n]` is scoped per (template, city). Should its counter be global? | Yes: one global sequence for PGR_COMPLAINT. Counting per city would give every city `PGR-00000001`, a duplicate. |
| 2 | Should counters reset when the financial year, year or month in the ID changes? | Yes, but only when that period is printed in the ID. A new period starts again from `start`; `PGR-` prints no period, so it never resets. |
| 3 | Is it acceptable that IDs have gaps and are not in strict time order? | Yes. Each instance takes blocks of numbers, so instances interleave (`000001`, `000101`, `000201`, `000002`) and a restart leaves a gap. Only uniqueness is guaranteed ([ADR-001](docs/adr/0001-sequences-in-memory-blocks.md), [ADR-006](docs/adr/0006-per-instance-memory-no-redis.md)). |
| 4 | When a sequence outgrows its width (`SEQ_WS:4` past 9999), should the ID grow or fail? | It grows (`WS-AMR-10000-11-2026`), with a warning logged once per counter. |
| 5 | Is Indian time the reference for FY and date placeholders? | Yes, Asia/Kolkata, read once per request so every ID in a bulk call shares the date. |
| 6 | Is there a maximum bulk `count`? | 1–1000. Tested with 50 parallel workers against 3 instances. |
| 7 | Must the recent-IDs view survive restarts or span instances? | No: in memory, per instance (ADR-006). |
| 8 | The email mentions a spec, 2–3 ADRs and one API prototype; the brief asks for a full system. Which governs? | Built the full system; 6 ADRs are in [`docs/adr/`](docs/adr/). |

## Ambiguities and decisions

### Templates and placeholders

| Ambiguity | Decision | Why |
|---|---|---|
| A template with no `[CITY]` (`PGR-[SEQ:8]`) | One global counter for it | Per city, IDs would repeat across cities |
| A named counter (`SEQ_EST_ALLOT`) with no `counters` entry | The template loads; issuing returns `422 COUNTER_NOT_CONFIGURED`. Never auto-created | Required by the brief; an invented `start` could collide with a real one later |
| `[SEQ:n]` width is "zero-padded to n digits" | `n` is a minimum; longer values print in full | Truncating would wrap round into duplicates |
| What scopes a counter | Exactly the variable parts the ID prints: the city only if `[CITY]` is printed, the period only if a date placeholder is | A part that isn't printed can't tell two IDs apart ([ADR-003](docs/adr/0003-counter-scoping-multi-tenancy.md)) |
| One named counter used by two templates | One shared sequence per (counter, city, period) | Matches "named counter scoped per city" |
| Meaning of `start` | The first value issued; implicit `[SEQ:n]` counters start at 1; a missing `start` means 1 | Most natural reading |
| A template with no sequence placeholder, or two | Startup fails | With none, every ID would be identical; with two, which one makes it unique is unclear |
| Unknown placeholder syntax (`[FOO]`, `[FY:yyyy]`) | Startup fails, naming the template and the character position | A typo should never reach production silently |
| `[CY:fmt]` patterns | Any `java.time` pattern (`yyyy`, `MM-yyyy`); an invalid one fails startup | Covers the brief's examples without inventing a grammar |
| Clock | Asia/Kolkata, read once per request | 31 Mar 20:00 UTC is already 1 Apr in India; a bulk call must not straddle a boundary |

### Cities and requests

| Ambiguity | Decision | Why |
|---|---|---|
| `city` = tenant ID or city code | Both accepted, case-insensitive (`pb.amritsar`, `AMR`, `amr`); responses echo the canonical values | The brief allows either |
| Template IDs | Matched exactly (case-sensitive) | The brief only asks for flexibility on the city |
| City codes printed into IDs | Must be letters and digits; duplicates fail startup | A `-` or `/` in a code could make two cities' IDs collide |
| Bulk size | `count` must be a JSON integer, 1–1000 (`"5"`, `2.5`, `true` are rejected) | Bounds the response; avoids silent coercion |
| A bulk call that fails half-way | All or nothing: nothing is returned; numbers already taken become a gap | Gaps are allowed, partial results are confusing |
| Which error wins when several apply | Request shape and `count` first, then template, city, counter | A malformed request is reported as such |

### Errors

Every failure uses one shape: `{"error":{"code":"…","message":"…","details":{…}}}`.

| Code | HTTP | When |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Body missing or not JSON, `templateId` / `city` missing |
| `INVALID_COUNT` | 400 | `count` not a whole number from 1 to 1000 |
| `UNKNOWN_TEMPLATE` | 404 | `templateId` not in `templates.json` |
| `UNKNOWN_CITY` | 404 | `city` matches no tenant ID or city code |
| `COUNTER_NOT_CONFIGURED` | 422 | The template's named counter has no `counters` entry |
| `SEQUENCE_UNAVAILABLE` | 503 | Postgres unreachable when a new block is needed |
| `SERVICE_UNAVAILABLE` | 503 | No backend instance can answer (from Nginx, with `Retry-After`) |
| `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` | 404, 405, 415 | Unknown path, wrong method, wrong content type |
| `INTERNAL_ERROR` | 500 | Anything else; the response carries a correlation ID that is also logged |

Bad configuration (`templates.json`, `cities.csv`) stops startup with one line naming the file and line, for example `Invalid configuration: /data/cities.csv line 11: expected 3 fields, found 2`.

### Running several instances

| Ambiguity | Decision | Why |
|---|---|---|
| Where the "next number" lives | A Postgres sequence per counter key, `INCREMENT BY 100`; each instance takes a block with one `nextval` and issues from memory | `nextval` never returns a value twice, so blocks never overlap across instances, restarts or crashes ([ADR-001](docs/adr/0001-sequences-in-memory-blocks.md)) |
| Counters with few values (`SEQ_WS:4` from 1000 has 9,000) | Blocks of 10 instead of 100 | Gaps would otherwise waste the narrow range |
| Shared counter or cache (Redis) | Not used | A network hop per ID; the local blocks need one Postgres call per 100 IDs ([ADR-006](docs/adr/0006-per-instance-memory-no-redis.md)) |
| Recent-IDs view | Last 50 per (template, city), in each instance's memory; the UI says which instance answered | A convenience, not an audit trail (ADR-006) |
| Front door | Nginx round-robin over 3 replicas, no API gateway | Nothing in scope needs gateway features ([ADR-004](docs/adr/0004-nginx-over-api-gateway.md)) |
| Changing templates or cities | Read once at startup; restart to apply. Adding is safe; changing an existing template's format is not (use a new template ID) | [ADR-005](docs/adr/0005-config-loaded-at-startup.md) |

## Out of scope

- Authentication and authorisation (any caller can issue for any tenant).
- Editing templates or cities at runtime (restart to apply, ADR-005).
- Cancelling, reusing or reserving IDs.
- An audit history of issued IDs, and a recent-IDs view shared across instances.
- Strict time order of IDs across instances.
- Multi-region deployment and data residency.
- Native-image builds and Kubernetes autoscaling (the "another day" items in [`plan.md`](plan.md)).

## What comes with this spec

| File | What it is | How to use it |
|---|---|---|
| [`docs/Architecture and Implementation Plan.pdf`](docs/Architecture%20and%20Implementation%20Plan.pdf) | The architecture and implementation plan, written and reviewed in Claude **before any code**: decisions on the brief's open points, the allocation design, the API contract, the code structure and the build order with a done-check per step | Read first for the design; the same content is in [`docs/architecture.md`](docs/architecture.md) |
| [`docs/adr/`](docs/adr/) | Six architecture decision records: sequences with in-memory blocks, Quarkus, counter scoping and tenancy, Nginx over an API gateway, config loaded at startup, per-instance memory without Redis | One decision per file, each with options, consequences and "another day" |
| [`plan.md`](plan.md) | Milestones as they went, data model, API sketch, test strategy, "another day" | Read after this spec |
| [`ai-log.md`](ai-log.md) | Where the AI was wrong and how it was caught; the step-by-step log is in [`docs/ai-log.md`](docs/ai-log.md) | Read for how Claude Code was driven |
| [`docs/claude-code-session.txt`](docs/claude-code-session.txt) | The exported Claude Code session, unedited | Read with [`docs/prompt-index.md`](docs/prompt-index.md), which summarises each prompt with its line numbers |
| [`README.md`](README.md) | Run instructions, a 10-step UI walkthrough, manual `curl` checks | Start here to run it |
| [`egov-poc-id-gen.postman_collection.json`](egov-poc-id-gen.postman_collection.json) | Postman collection: ping, health, templates, cities, single issue, bulk issue, recent IDs | Import into Postman; every request targets `http://localhost:8080` |
| [`loadtest/loadtest.py`](loadtest/loadtest.py) | Concurrency test: 50 OS processes against 3 replicas, mixed single and bulk, fails on any duplicate | `docker compose --profile test run --rm loadtest` |
| [`loadtest/idgen.jmx`](loadtest/idgen.jmx) | JMeter throughput and latency test, 50 users for 60 s, every template × city | `docker compose --profile jmeter run --rm jmeter` |
| [`loadtest/out/`](loadtest/out/) | Committed results: load test JSON summaries (110,176 IDs, 0 duplicates; 0 again with every backend restarted mid-run) and the JMeter HTML report (48,911 requests, 0 errors, p95 56 ms) | Open `loadtest/out/jmeter/report/index.html` locally |
| [`nazaarblue/idgen`](https://hub.docker.com/r/nazaarblue/idgen) on Docker Hub | Prebuilt image, `1.0.0` and `latest`, for amd64 and arm64 (Apple Silicon) | See "Run the prebuilt image" in the README; `docker compose up` builds from source and does not need it |
| The web UI | Issue single and bulk IDs, see errors and the recent IDs of the answering instance | http://localhost:8080 after `docker compose up` |
