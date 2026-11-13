# ID Generation Service — instructions for Claude Code

The full spec is **[docs/architecture.md](docs/architecture.md)**. Read it before any change.
The API contract and error codes there are binding, as are the ADRs it links to in `docs/adr/` (allocation: ADR-001, counter scoping: ADR-003).

## Rules
- Java 25 (LTS), Quarkus 3.x, Maven wrapper (`./mvnw`), one module, package `org.egov.idgen`.
- Extensions: `rest`, `rest-jackson`, `reactive-pg-client`, `smallrye-health`, `hibernate-validator`, `config-yaml`. **No ORM, no tables** — Postgres holds only sequences.
- **Never modify anything in `data/`.** It must stay byte-for-byte as provided.
- Follow the API contract and error codes in the spec exactly. DTOs are Java `record`s.
- Locks are per counter key, never global. Never call `nextval` per ID — always go through `BlockAllocator`. If endpoints run on virtual threads, use `ReentrantLock` instead of `synchronized`.
- Block size comes from `pg_sequences.increment_by`, never from app config.
- Every step ends with `./mvnw test` green and `docker compose up --build` working.
- **Never commit or push until the author has reviewed the step and says to.** Leave changes uncommitted and summarise them for review.
- When unsure, choose the option that **cannot produce a duplicate**, and add a line to `docs/NOTES.md`.
- After every step, append an entry to `docs/ai-log.md`: what was asked, what Claude did, what went wrong or was corrected.

## Commands
- Tests: `./mvnw test` (needs Docker: Dev Services starts Postgres)
- Dev mode: `./mvnw quarkus:dev`
- Full stack: `docker compose up --build` → http://localhost:8080
- Load test: `docker compose --profile test run --rm loadtest`

## Layout
- `src/main/java/org/egov/idgen/{config,template,sequence,issue,recent,api}` — see "Code structure" in the spec
- `src/main/resources/META-INF/resources/index.html` — static UI
- `nginx/nginx.conf` — round-robin to 3 backend replicas
- `loadtest/loadtest.py` — multiprocess duplicate check
- `docs/adr/` — ADRs 0001–0006
- `egov-poc-id-gen.postman_collection.json` — Postman collection (the author's); keep it in step with the API
- `docs/NOTES.md` — decisions and assumptions made while building
- `docs/ai-log.md` — running log of the AI-assisted build
