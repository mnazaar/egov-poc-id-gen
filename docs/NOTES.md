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
