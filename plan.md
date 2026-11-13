# Plan: Templated ID Generation Service

What was built, in what order, and what comes next. The plan written before any code is [`docs/architecture.md`](docs/architecture.md) (also as a PDF: [`docs/Architecture and Implementation Plan.pdf`](docs/Architecture%20and%20Implementation%20Plan.pdf)); the decisions are in [`spec.md`](spec.md) and [`docs/adr/`](docs/adr/).

## How the work was done

1. **Architecture first.** The architecture and implementation plan was written and reviewed in Claude before any code: decisions on the brief's open points, the allocation design, the API contract, the code structure, and a step-by-step build order with a done-check per step.
2. **Built with Claude Code, one step at a time.** [`CLAUDE.md`](CLAUDE.md) points every session at the plan and its rules (no ORM, never touch `data/`, per-key locks, "choose the option that cannot produce a duplicate"). Each step ended with tests green and the full Docker stack working, and was reviewed by the author before it was committed.
3. **Logged as it went.** [`docs/NOTES.md`](docs/NOTES.md) records each decision made while building; [`docs/ai-log.md`](docs/ai-log.md) records each step, including where the AI was wrong.

## Milestones as they went

Times are IST on 13 Nov 2026, from the git history. Each commit links to GitHub; every step was reviewed by the author before it was committed.

| Time | Milestone | Done-check result | Commits |
|---|---|---|---|
| 05:12 | Repository created; plan already written and reviewed | — | [`e0790ff`](https://github.com/mnazaar/egov-poc-id-gen/commit/e0790ff) |
| 05:15–06:01 | **Skeleton.** Quarkus 3.40 on Java 25, multi-stage Dockerfile, compose with Postgres 16 + 3 backend replicas + Nginx, `GET /api/ping`, ADR-004 (Nginx, not an API gateway) | `/api/ping` through Nginx rotates over 3 instances; readiness UP | [`4a450f0`](https://github.com/mnazaar/egov-poc-id-gen/commit/4a450f0), [`cdbbd47`](https://github.com/mnazaar/egov-poc-id-gen/commit/cdbbd47) |
| 06:01–06:43 | **Config + templates.** Template parser and renderer, counter keys, city and template registries, `GET /api/templates`, `GET /api/cities`; data files stored byte-for-byte; one-line startup error for bad config | Render tests for all six templates, FY boundary, padding growth, PGR global key, EST rejection | [`322893a`](https://github.com/mnazaar/egov-poc-id-gen/commit/322893a), [`168ce91`](https://github.com/mnazaar/egov-poc-id-gen/commit/168ce91) |
| 06:43–07:03 | **Allocation + API.** Sequence store, block allocator, issue service, `POST /api/ids`, `POST /api/ids/bulk`, `GET /api/ids/recent`, one error mapper; ADR-005 (config loaded at startup) | Single + bulk for all 50 template × city pairs; every error code in the documented shape; 2,000 parallel requests, 0 duplicates | [`20b1f01`](https://github.com/mnazaar/egov-poc-id-gen/commit/20b1f01) |
| 07:03–07:42 | **Concurrency test + JMeter.** `loadtest.py`, JMeter plan, JSON 503 from Nginx when no backend is up; ADR-006 (per-instance memory, no Redis) | 110,176 IDs, 0 duplicates; again 0 with every backend restarted mid-run; JMeter 0 errors at 817 req/s | [`551cfa0`](https://github.com/mnazaar/egov-poc-id-gen/commit/551cfa0) (ADRs linked from the plan), [`c4df398`](https://github.com/mnazaar/egov-poc-id-gen/commit/c4df398), [`2a08eea`](https://github.com/mnazaar/egov-poc-id-gen/commit/2a08eea) (ADR-006) |
| 07:42–08:00 | **UI.** One static page: dropdowns, count, issue, results, recent panel per instance | Driven in headless Edge: bulk, single, the 422 and an invalid count | [`fb19a86`](https://github.com/mnazaar/egov-poc-id-gen/commit/fb19a86) |
| 08:00 onwards | **Packaging and documents.** Postman collection, multi-platform image on Docker Hub (`nazaarblue/idgen`), `spec.md`, `plan.md`, AI log | Image pulled from Docker Hub and run per the README | [`88eb0b6`](https://github.com/mnazaar/egov-poc-id-gen/commit/88eb0b6) |

The brief's time box is 4 hours. The core service (steps 1–5) was finished in about 2 hours 50 minutes; the remaining time went on packaging, evidence and documents.

## Data model

**Postgres holds only sequences. There are no tables** ([ADR-001](docs/adr/0001-sequences-in-memory-blocks.md)).

| What | Where | Lifetime |
|---|---|---|
| The highest number handed out per counter key | One Postgres sequence per key, e.g. `seq_pg_receipt_amr_2026_27`, `seq_pgr_complaint`, `seq_ws_amr_11_2026` | Durable |
| Each instance's current block (`next`, `end`) per key | Instance memory, one lock per key | Until restart (unused numbers become a gap) |
| Recent IDs: last 50 per (template, city) | Instance memory | Until restart ([ADR-006](docs/adr/0006-per-instance-memory-no-redis.md)) |
| Templates and cities | Read from `data/` at startup, templates pre-parsed into tokens | Until restart ([ADR-005](docs/adr/0005-config-loaded-at-startup.md)) |

- **Counter key** = the counter (template ID for `[SEQ:n]`, or the named counter) + city if `[CITY]` is printed + the rendered period if a date placeholder is printed ([ADR-003](docs/adr/0003-counter-scoping-multi-tenancy.md)).
- **Sequence** = `CREATE SEQUENCE IF NOT EXISTS … START WITH <start> INCREMENT BY 100` (10 for counters with fewer than 10,000 values in their width). Created at startup for the current period, otherwise on first use.
- **Allocation:** `SELECT nextval(…) FROM generate_series(1, k)` takes k blocks in one round trip. The block size is read back from `pg_sequences`, never from config, so instances can never disagree about it.

41 sequences exist after startup: 4 city-scoped templates × 10 cities + one global PGR sequence; EST_ALLOTMENT has none.

## API sketch

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/ids` | Issue one ID: `{"templateId":"PG_RECEIPT","city":"pb.amritsar"}` |
| POST | `/api/ids/bulk` | Issue `count` IDs (1–1000): `{"templateId":"TL_LICENSE","city":"LDH","count":3}` |
| GET | `/api/ids/recent?templateId=&city=&limit=50` | Recently issued IDs on the instance that answers |
| GET | `/api/templates` | Templates, with whether each can issue |
| GET | `/api/cities` | Cities |
| GET | `/q/health/ready` | Readiness: configuration loaded and Postgres reachable |
| GET | `/` | The web UI |

```http
POST /api/ids/bulk
{ "templateId": "TL_LICENSE", "city": "LDH", "count": 3 }

200 OK
{ "ids": ["TL/LDH/2026/00001", "TL/LDH/2026/00002", "TL/LDH/2026/00003"],
  "count": 3, "templateId": "TL_LICENSE", "tenantId": "pb.ludhiana", "cityCode": "LDH",
  "issuedAt": "2026-11-13T07:12:09+05:30" }
```

Errors share one shape, `{"error":{"code","message","details"}}`; the codes are listed in [`spec.md`](spec.md#errors).

## Test strategy

| Layer | What it proves | How to run |
|---|---|---|
| **Unit** (parser, renderer, loader, sequence names, block allocator) | All six templates render correctly; FY turns on 1 April in IST; padding grows; PGR has one global key; EST is rejected; bad files fail with file and line; 32 threads over 3 simulated instances never get the same number | `./mvnw test` |
| **API** (Quarkus test + throwaway Postgres) | Single and bulk for all 50 template × city pairs; every error code and its shape; 1,000-ID bulk across 100 blocks; 16 parallel clients with no duplicates; the 41 sequences exist with the right block sizes | `./mvnw test` (207 tests, Docker needed) |
| **Load test** (50 OS processes, 3 replicas behind Nginx) | Zero duplicates under mixed single and bulk load, and with every backend restarted mid-run | `docker compose --profile test run --rm loadtest` |
| **JMeter** (50 users, 60 s) | Throughput and latency; every response is 200 with a well-formed ID | `docker compose --profile jmeter run --rm jmeter` |
| **UI** | Issue, bulk, errors, recent panel, in a real browser | Manual steps in the [README](README.md#testing-with-the-ui); also driven in headless Edge during the build |
| **Postman** | Every endpoint by hand | [`egov-poc-id-gen.postman_collection.json`](egov-poc-id-gen.postman_collection.json) |
| **Fresh clone** | `docker compose up` is the only command | Clone into an empty folder and run it |

Results are committed under [`loadtest/out/`](loadtest/out/) and summarised in the README:

| Run | Requests | IDs | Duplicates | Notes |
|---|---:|---:|---:|---|
| Load test, 30 s | 16,035 | 110,176 | **0** | Even spread over 3 instances |
| Load test, all backends restarted mid-run | 19,378 | 28,151 | **0** | Errors during the outage were JSON `503 SERVICE_UNAVAILABLE` |
| JMeter, 60 s | 48,911 | — | — | 0 errors, 817 req/s, p95 56 ms, p99 84 ms |

## With another day

| Item | Why | Where |
|---|---|---|
| Native image and Kubernetes autoscaling | Millisecond startup makes scale-out useful during a spike; prove zero duplicates while pods come and go | [ADR-002](docs/adr/0002-quarkus.md) |
| Block size that adapts to load | Bigger blocks for hot counters, smaller for quiet ones and for pods that scale down | [ADR-001](docs/adr/0001-sequences-in-memory-blocks.md) |
| Authentication at a gateway | Take the tenant from a verified token, not the request body; per-tenant rate limits | [ADR-003](docs/adr/0003-counter-scoping-multi-tenancy.md), [ADR-004](docs/adr/0004-nginx-over-api-gateway.md) |
| Reload templates and cities without a restart | Validate, swap atomically, refuse format changes that would re-scope an existing template | [ADR-005](docs/adr/0005-config-loaded-at-startup.md) |
| Shared recent-IDs view | Publish issued IDs to Redis asynchronously, never on the numbering path | [ADR-006](docs/adr/0006-per-instance-memory-no-redis.md) |
| Audit trail | An `issued_ids` table written in batches off the request path | ADR-001 |
| Image published by CI | A GitHub Actions workflow that builds and pushes on every tag | — |
| One-at-a-time restart in the load test | Shows Nginx routing round a restarting instance with near-zero errors | — |
