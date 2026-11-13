# AI log

How this POC was built with Claude Code (Opus 5.5), step by step. Updated after every step; newest last.
The spec Claude worked from is [architecture.md](architecture.md).

## 2026-11-13 — Step 1: skeleton

**Asked:** read the architecture doc and scaffold Quarkus.

**Claude did:**
- Read the architecture doc (a Claude Doc) and saved a markdown copy as `docs/architecture.md`.
- Generated the project from code.quarkus.io (no Maven or Quarkus CLI installed locally): Quarkus 3.40.1 with `rest`, `rest-jackson`, `reactive-pg-client`, `smallrye-health`, `hibernate-validator`, `config-yaml`.
- Added `GET /api/ping` (returns the instance hostname), a test for it and for `/q/health/ready`, `application.yaml`, a multi-stage `Dockerfile`, `docker-compose.yml` (Postgres 16, 3 backend replicas, Nginx on :8080, `loadtest` profile), `nginx/nginx.conf`, `CLAUDE.md`, `docs/NOTES.md`.
- Verified: `./mvnw test` green; `docker compose up --build` serves `/api/ping` through Nginx, rotating across all 3 replicas; readiness UP with the Postgres check.

**Went wrong / corrected:**
- Tests first failed because Docker Desktop wasn't running (Dev Services needs it); Claude started it.
- The Docker build failed: the Maven wrapper verifies the `.zip` checksum but downloads the `.tar.gz` when `unzip` is missing. Fixed by installing `unzip` in the build stage.
- After a rebuild, Nginx returned 502 for ~5 s until it re-resolved the new backend addresses. Recovers without help; worth knowing for the restart test.

**Human decisions:**
- Java 21 (from the spec) → Java 25, the latest LTS. Updated in the pom, Dockerfile, `CLAUDE.md`, and the spec (both the Claude Doc and the local copy).
- `ai-log.md` lives in `docs/` and is kept up to date after every step, not written at the end.
- `NOTES.md` moved into `docs/` too, so all working documents sit together.
- Asked for an ADR on why Nginx rather than an API gateway: Claude wrote ADR-004 (`docs/adr/0004-nginx-over-api-gateway.md`) and added it to the spec.
- Asked for the other ADRs in `docs/adr/` too: Claude copied ADR-001 to ADR-003 from the spec into their own files, word for word.
- Asked for the Nginx config in ADR-004 and for manual test steps: Claude added both (config section in ADR-004, "Manual testing" in the README).
- Asked to add JMeter to the plan: Claude added a throughput test (`loadtest/idgen.jmx`) beside the Python duplicate check, in the Tests list, the 6:15 step, the cut order (cut first) and the repo layout.
