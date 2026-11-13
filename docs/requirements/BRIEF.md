# Take-Home Assignment — Templated ID Generation Service

Thank you for interviewing with us. This is a **4-hour** assignment. When you hit 4 hours, stop and submit what you have — scoping to the time box is part of what we assess. AI coding tools (Claude Code, Cursor, Copilot, etc.) are **required**, not just permitted: how you drive them is part of the evaluation.

## The problem

Government services issue human-readable, formatted identifiers — receipt numbers, license numbers, complaint numbers — like `PG-AMR-2026-27-000431`. Build a service that issues IDs from configurable templates.

**An occasional gap in the sequence is acceptable; a duplicate is never acceptable.**

Template definitions and the city master are provided in `data/` (`templates.json`, `cities.csv`). Placeholder grammar:

| Placeholder | Meaning |
|---|---|
| `[CITY]` | the requesting city's `city_code` |
| `[FY:yyyy-yy]` / `[FY:yy-yy]` | current Indian financial year (April–March), e.g. `2026-27` / `26-27` |
| `[CY:fmt]` | current date rendered with `fmt` (`yyyy`, `MM-yyyy`, …) |
| `[SEQ:n]` | next counter value, zero-padded to `n` digits; counter is scoped per **(template, city)** |
| `[SEQ_NAME:n]` | like `[SEQ:n]` but uses the named counter `SEQ_NAME` (scoped per city), whose configuration lives in the `counters` section of `templates.json` |

### Requirements

1. **Issue endpoint**: given `templateId`, `city` (tenant or city code), return one formatted ID.
2. **Bulk endpoint**: issue `count` IDs in one call.
3. **Concurrency safety**: parallel requests — including bulk ones — must never produce a duplicate ID. **Your submission must include a concurrency/load test that demonstrates this** (spawning genuinely parallel workers against the running app).
4. Requests referencing an unknown template, unknown city, or a template whose named counter has no configuration must fail with a clear, well-formed error.
5. **Frontend**: a thin web UI — pick a template and city, enter a count, issue, and see the issued IDs (plus a simple view of recently issued IDs). Plain HTML/JS is fine; function over polish.

## What to build (end to end)

- **Backend** (any language): API + persistence, loading `data/` at startup.
- **Frontend**: as above.
- **Containerization**: a `docker-compose.yml` at the repo root. **`docker compose up` must be the only command needed** — build, start backend + frontend + database, load the template/city data, and document the URL to open. No undocumented steps.

## Deliverables

1. `spec.md` — the problem restated; every ambiguity you found, the decision you made, and why; what's explicitly out of scope.
2. `plan.md` — milestones, data model, API sketch, test strategy, what you'd do with another day.
3. Working code (backend + frontend) + tests (including the concurrency test and how to run it) + `docker-compose.yml` + `README.md` with run instructions.
4. `ai-log.md` — your prompts/transcript (or exported session), plus 3–5 sentences on where the AI was wrong and how you caught it.

## Ground rules

- The brief is deliberately underspecified in places. Make a sensible decision, write it in `spec.md`, and move on. You may email **one batch** of clarifying questions within 24 hours of receiving this — good questions count in your favor.
- Use the provided data files as-is; don't edit them.
- Any language/framework. We review by running `docker compose up` from a clean checkout and hammering your endpoints in parallel.

## Submission checklist

- [ ] `docker compose up` from a fresh clone works with no other steps
- [ ] Single + bulk issuance work for the provided templates and cities
- [ ] Your concurrency test runs (document the command) and demonstrates zero duplicates
- [ ] Error cases return clear, well-formed errors (not stack traces)
- [ ] `spec.md`, `plan.md`, `ai-log.md`, `README.md` present
