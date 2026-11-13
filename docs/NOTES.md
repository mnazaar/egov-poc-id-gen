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
