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

## 2026-11-13 — Step 2: config + templates

**Asked:** DataLoader, TemplateRegistry, CityRegistry, TemplateParser, CompiledTemplate.render, CounterKey, plus unit tests. Done when render tests pass for all six templates, the FY boundary, padding growth, PGR global key and EST rejection, and `GET /api/templates` and `GET /api/cities` return data.

**Claude did:**
- Found the data files missing from the repo, located the candidate pack in Downloads, and copied `data/` in unchanged. Noticed `cities.csv` has CRLF line endings and added `.gitattributes` so git stores it byte-for-byte; verified with `git hash-object`.
- `template`: sealed `Token` (Literal, CityCode, FinancialYear, CalendarDate, Sequence), `TemplateParser` (errors name the template and position), `CompiledTemplate` (render from tokens, counter key, COUNTER_NOT_CONFIGURED), `CounterKey`, `CounterSpec`.
- `config`: `IdgenConfig`, `DataLoader` (startup; CSV and JSON errors name file and line), `CityRegistry` (tenant ID or code, case-insensitive), `TemplateRegistry`, a readiness check for loaded config.
- `api`: `ErrorCode`, `IdgenException`, `MetaResource` (`/api/templates`, `/api/cities`).
- 55 tests green: parser, rendering of all six provided templates, FY boundary (incl. 31 Mar 20:00 UTC = 1 Apr IST), padding growth, PGR global key, EST rejection, loader errors, API. Endpoints verified through Nginx in Docker.

**Went wrong / corrected:**
- Claude first copied `BRIEF.md` into `docs/`, then removed it: committing the employer's assignment text to a public repo is the author's call.
- Claude left an unused helper in `CityRegistry` and rewrote the class.
- One parser test relied on an obscure `java.time` pattern rule; Claude replaced it with a reserved character that always fails.
- Found a spec inconsistency (WS sequence examples omit the month that the scoping rule requires); the code follows the rule, noted in `NOTES.md`.
- The author tested a broken `cities.csv` and saw the right message buried under a Quarkus stack trace, which the spec forbids. Claude now logs one line and stops startup with `PreventFurtherStepsException`. While there, Claude found an invisible literal byte-order-mark character in the source and replaced it; two attempts through the shell mangled the escape before an editor edit fixed it.
- The author asked to review before every commit; `CLAUDE.md` now says so.

## 2026-11-13 — Step 3: allocation + API

**Asked:** SequenceStore, BlockAllocator, IdService, IdResource, ErrorMapper, RecentIds, plus API tests. Done when single + bulk work for every template × city and every error code returns the documented shape.

**Claude did:**
- `sequence`: `SequenceNames`, `SequenceSpec` (block size, small blocks for narrow counters), `SequenceStore` (reactive PG client; `CREATE SEQUENCE IF NOT EXISTS`, `nextval` × k via `generate_series`, `increment_by` from `pg_sequences`; concurrent-create and dropped-sequence handling), `BlockAllocator` (per-sequence `ReentrantLock`, several blocks per trip for bulk), `SequenceWarmup` (creates the 41 sequences at startup).
- `issue.IdService`, `recent.RecentIds`, `api.IdResource` (`POST /api/ids`, `/api/ids/bulk`, `GET /api/ids/recent`), `api.ErrorMapper` (one shape for everything, correlation ID for 500s).
- 206 tests green, including all 50 template × city pairs single and bulk, every error code, 1,000-ID bulk across 100 blocks, and parallel requests with no duplicates.
- Verified on the real stack: 3 instances each took their own block (`000001`, `000101`, `000201`); 2,000 parallel requests through Nginx gave 22,564 IDs, 0 duplicates; with Postgres stopped, warm counters kept issuing and cold ones returned 503 SEQUENCE_UNAVAILABLE, recovering without a restart.

**Went wrong / corrected:**
- Startup ordering bug: the sequence warm-up ran before the config loaded and created 0 sequences in Docker, though tests passed. Claude had assumed injecting `DataLoader` loads it first; Quarkus injects a lazy proxy. Caught by checking `pg_sequences` on the real stack, not by tests. Fixed by calling the loader explicitly.
- Claude wrote a nonsense nested `Map` interface in a test and a regex that would have counted template IDs as issued IDs (false duplicates); both fixed before the first run.

## 2026-11-13 — ADR-005: config loaded at startup

**Asked:** will adding a new city fail, or create its sequences automatically? Then: record it as an ADR, since templates and cities are cached at startup and a change needs a restart (fixable with another day).

**Claude did:** verified with a copy of `cities.csv` plus `ka.hubli,HBL,Hubli` on an extra backend: after a restart it loaded 11 cities, created 4 new sequences (41 → 45) and issued `PG-HBL-2026-27-000001`; instances not restarted answered 404 UNKNOWN_CITY. Wrote ADR-005 with a table of which config changes are safe. While writing it, Claude spotted that editing an existing template's format can re-issue old IDs (the counter key changes, so a new sequence starts from `start`) and made "use a new template ID instead" an explicit rule. "Another day": validated hot reload with an atomic registry swap, a guard against format changes, and a reload event to all replicas.

## 2026-11-13 — Step 4: concurrency test + JMeter

**Asked:** `loadtest/loadtest.py` + compose `loadtest` profile, plus the JMeter plan `loadtest/idgen.jmx`. Done when 0 duplicates against 3 replicas, and again with `docker compose restart backend` mid-run; save the output for the README.

**Claude did:**
- `loadtest.py`: 50 OS processes (`multiprocessing.Pool`), random template × city, single or bulk 1–50, collects every ID, exits 1 on any duplicate. Reports throughput, status codes, errors and which backend served each request (Nginx `X-Upstream`).
- Results: 110,176 IDs in 31 s across 3 balanced instances, 0 duplicates; with all backends restarted mid-run, 66,839 IDs, 0 duplicates (13,936 × 502 while they were down).
- JMeter: `idgen.jmx` (50 users, 60 s, CSV of every template × city, assertions on status and ID pattern), a Dockerfile that installs JMeter from the Apache archive with a SHA-512 check, compose profile `jmeter`. Result: 48,911 requests, 0 errors, 817 req/s, p95 56 ms.
- README sections with the commands and saved output.

**Went wrong / corrected:**
- The `.jmx` header comment contained `--profile`; XML comments can't contain `--`, so JMeter refused the file. Fixed the comment.
- The CSV regexes used `\d{6,}`; the comma split the CSV column, so every assertion failed (100 % errors with HTTP 200). Claude moved CSV generation into `make_requests.py` with comma-free patterns and an assertion that every row has 7 columns. Two attempts to patch the CSV in place through the shell failed on backslash escaping first.
- `docker compose build -q` silently didn't rebuild the JMeter image (same trap as step 2); Claude spotted it by inspecting the image's entrypoint and now uses a normal build.
- One JMeter trial returned 502 for every request, right after compose restarted Nginx for the run; it did not recur in five later runs and wasn't explained.

**Follow-up, JSON when no backend is up:** the author asked for Claude's suggestion: Nginx's HTML 502 during a full restart broke "well-formed errors". Claude added `error_page 502 503 504 = @no_backend` returning `503 SERVICE_UNAVAILABLE` in the API's shape with `Retry-After`, verified both that it applies with all backends stopped and that a backend's own `503 SEQUENCE_UNAVAILABLE` still passes through, re-ran the restart load test (28,151 IDs, 0 duplicates, every error now JSON), made the load test back off on any 5xx, and updated ADR-004 in the doc and the repo.

## 2026-11-13 — ADR-006: per-instance memory, no Redis

**Asked (author's decision):** recent IDs come from each instance's memory; with another day they could move to a central cache such as Redis. Keeping numbers in local blocks is intentional to avoid round trips, so Redis is not used for that: it would add network latency. IDs not being issued in exact sequence, and jumping by instance, is acceptable. Document it in the README and an ADR.

**Claude did:** wrote ADR-006 (both behaviours, the four options for the recent view, and "another day": publish issued IDs to Redis asynchronously so issuing isn't slowed, Redis never deciding a number), added it to the Claude Doc and the ADR list, and added a "How numbering behaves (by design)" section near the top of the README so reviewers see it before testing. Also noted that the UI must label the recent list as per instance.

## 2026-11-13 — Step 5: UI

**Asked:** `index.html` with template + city dropdowns from the meta endpoints, a count field, issue, a results list, and a recent-IDs panel.

**Claude did:** one static page (no libraries), served by Quarkus at `/`: dropdowns from `/api/templates` and `/api/cities` (EST marked, still selectable to show the 422), count 1 → single, 2–1000 → bulk, results per batch with the serving instance and a copy button, API errors shown with code and message, recent panel labelled per instance (ADR-006), light/dark theme, one column on narrow screens. Test: page served at `/`. Verified in headless Edge: screenshots at 1200 and 520 px, then drove the page over the DevTools protocol (Node's built-in WebSocket) to issue a bulk, a single, the EST error and a count of 0.

**Went wrong / corrected:**
- The count label broke over three lines (CSS grid treated the text and a nested span as separate rows); caught from the screenshot, fixed by wrapping the label text.
- A re-screenshot looked unfixed because headless Edge reused its cached page; a fresh profile showed the fix.
- A 400 px screenshot looked cut off; that was the browser's minimum window width, not the page (520 px rendered correctly).
- After issuing, the recent panel was empty because the refresh reached another instance: correct per ADR-006, but confusing, so the empty message now explains it.

**Follow-up, Postman:** the author added `egov-poc-id-gen.postman_collection.json` and asked for it to be listed with the required documents. Claude checked it held no tokens or private URLs, then added a README section describing each request, and listed it in the spec (Claude Doc and local copy) and `CLAUDE.md`.

**Follow-up, README UI walkthrough:** the author asked how to test with the UI. Claude added a 10-step table (single, interleaving across instances, bulk, PGR global counter, EST hint and 422, invalid count, per-instance recent panel, all backends down, Postgres down), then corrected two rows that promised more than the system guarantees: example numbers only appear on a fresh database, and PGR numbers only continue exactly when the same instance serves both requests.
