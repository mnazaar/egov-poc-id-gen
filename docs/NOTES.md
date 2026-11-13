# Working notes

Decisions made while building, in order. Feeds `ai-log.md` and `spec.md`.

- 2026-11-13 05:15 — Scaffolded from code.quarkus.io (Quarkus 3.40.1). Added `config-yaml` so settings live in `application.yaml`.
- 2026-11-13 05:15 — Nginx re-resolves `backend` every 5s (`server … resolve`) so a restarted replica rejoins the pool. POSTs are not retried after being sent to an upstream.
- 2026-11-13 05:15 — Runtime image is `eclipse-temurin:25-jre-alpine` (multi-arch, has `wget` for the healthcheck).
- 2026-11-13 05:30 — Moved from Java 21 to Java 25 (latest LTS) at the author's request; spec updated to match.
- 2026-11-13 05:35 — ai-log moved to `docs/ai-log.md` and kept up to date after every step (author's request). Spec updated.
- 2026-11-13 05:40 — Moved `NOTES.md` into `docs/` (author's request). Spec updated.
- 2026-11-13 05:50 — ADR-004: Nginx over an API gateway (author's request). Added to the spec and `docs/adr/`.
- 2026-11-13 05:55 — ADR-001 to ADR-003 copied from the spec into `docs/adr/`. The spec stays the source; keep both in sync.
- 2026-11-13 06:00 — Nginx config added to ADR-004 (spec + docs/adr). Tidied the config's comments; behaviour unchanged.
- 2026-11-13 06:05 — README: manual testing steps for step 1. Extend with ID endpoints and error cases as they land.
- 2026-11-13 06:10 — JMeter added to the plan for throughput/latency only; `loadtest.py` remains the duplicate proof. JMeter is the first thing to cut if short on time.
- 2026-11-13 06:10 — JMeter added to the plan for throughput/latency only; `loadtest.py` remains the duplicate proof. JMeter is the first thing to cut if short on time.
- 2026-11-13 06:40 — Data copied from the candidate pack unchanged. `cities.csv` has CRLF endings; `.gitattributes` marks `data/**` as `-text` so git never rewrites them (a fresh clone on Linux gets the same bytes). The brief itself is not committed.
- 2026-11-13 06:40 — A template must contain exactly one `[SEQ…]` placeholder. With none, every ID it renders would be identical; with two, which one is unique is unclear. Both fail startup.
- 2026-11-13 06:40 — City codes must be letters and digits only, since they're printed into IDs: a code containing `-` or `/` could make IDs from different cities collide. Duplicate tenant IDs or codes (case-insensitive) fail startup.
- 2026-11-13 06:40 — Counter key = kind (implicit/named) + counter + city (only if `[CITY]` is printed) + the rendered date placeholders (only those printed). The kind keeps a template called `SEQ_X` from sharing a sequence with a named counter `SEQ_X`.
- 2026-11-13 06:40 — Spec inconsistency: ADR-001/003 show the WS sequence as `seq_ws_amr`, but the decisions table scopes `SEQ_WS` by city + month (it prints `[CY:MM-yyyy]`). Code follows the scoping rule: the key includes the month. Fix the examples when writing the ADR files' final versions.
- 2026-11-13 06:40 — A named counter entry without `start` defaults to 1; a negative `start` fails startup.
- 2026-11-13 06:40 — Template IDs are matched exactly (case-sensitive); only cities are case-insensitive, as the spec says.
- 2026-11-13 07:00 — Sequence names follow the spec examples (`seq_pg_receipt_amr_2026_27`, `seq_pgr_complaint`); named counters drop their `SEQ_` prefix (`seq_ws_amr_11_2026`). If two keys ever map to one name they share a sequence: still no duplicates, only shared numbering. Names over 63 chars are cut and get an 8-char hash.
- 2026-11-13 07:00 — Block size: 100, or 10 when a counter has fewer than 10,000 values inside its printed width (`SEQ_WS:4` from 1000 has 9,000). TL `[SEQ:5]` (99,999 values) keeps 100. Allocation always uses `increment_by` read from `pg_sequences`.
- 2026-11-13 07:00 — Startup creates sequences for the current period only (ADR-001 says current and next). Next period's sequences are created on first use; one `CREATE SEQUENCE IF NOT EXISTS` costs a few ms once.
- 2026-11-13 07:00 — Request validation (body shape, missing fields, `count`) runs before lookups, so `{"templateId":"NOPE","count":0}` is INVALID_COUNT, not UNKNOWN_TEMPLATE.
- 2026-11-13 07:00 — Framework errors use the same shape with HTTP-name codes not in the spec's table: `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), `UNSUPPORTED_MEDIA_TYPE` (415). Add them to the API contract.
- 2026-11-13 07:00 — `count` must be a JSON integer: `"5"`, `2.5`, `true`, `null` are INVALID_COUNT.
- 2026-11-13 07:00 — Open: the recent-IDs view is per instance (spec). Behind Nginx round-robin, the UI's recent list changes with whichever instance answers. Decide before the UI step.
- 2026-11-13 07:10 — ADR-005: config loaded once at startup, restart to apply. Editing an existing template's format is unsafe (can re-issue IDs); add a new template ID instead.
- 2026-11-13 07:20 — `docs/architecture.md` now links to the ADR files instead of repeating them; `docs/adr/` is the one local copy. The Claude Doc still holds the full ADRs.
- 2026-11-13 07:40 — Load test uses only the Python standard library, so the `python:3.12-slim` container needs no `pip install`. Errors don't fail the run (EST's 422s are expected; restarts cause 502s); only duplicates or zero IDs do.
- 2026-11-13 07:40 — JMeter runs from a small image built from the official Apache archive with a SHA-512 check, not a third-party JMeter image. Compose profile `jmeter`, separate from `test`, so `--profile test` stays fast.
- 2026-11-13 07:40 — Resolved 07:55 (see below). Was open: when every backend is down, Nginx returns its own HTML 502 page, not our JSON error shape. A JSON `error_page` in nginx.conf would keep "well-formed errors" true even then.
- 2026-11-13 07:55 — Nginx now answers `503 SERVICE_UNAVAILABLE` (JSON, `Retry-After: 5`) when no backend can answer. `proxy_intercept_errors off` keeps backend JSON errors (e.g. 503 SEQUENCE_UNAVAILABLE) untouched. Add SERVICE_UNAVAILABLE to the API contract's error table. Load test now backs off on any 5xx.
