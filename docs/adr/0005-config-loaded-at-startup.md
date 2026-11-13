## ADR-005: Templates and cities are loaded once at startup; changes need a restart

**Decision:** `templates.json` and `cities.csv` are read once at startup, validated, and held in memory (templates pre-parsed into tokens). Nothing re-reads them while the service runs, so adding or changing a city or template takes a restart of every backend instance. Accepted, 13 Nov 2026.

**Context.** The brief says to "load both files at startup" and treat them as configuration, not user input. Parsing once means no file I/O or string replacement per request (only rendering pre-parsed tokens), and a bad file is caught before the service takes traffic rather than halfway through a day.

**What a restart does (verified by adding `ka.hubli,HBL,Hubli` to a copy of `cities.csv`):**
- The instance logs `Loaded 6 templates and 11 cities` and creates the new city's sequences at startup (41 → 45); if Postgres is down then, they are created on first use.
- `PG-HBL-2026-27-000001` and `WS-HBL-1000-11-2026` issue immediately. `PGR_COMPLAINT` continues its one global counter.
- Instances not yet restarted answer `404 UNKNOWN_CITY` for the new city.

**Which changes are safe**

| Change | Safe? | Why |
|---|---|---|
| Add a city | Yes | New city code → new sequences; no existing ID can repeat |
| Add a template | Yes | New template ID → new sequences |
| Add a named counter (e.g. `SEQ_EST_ALLOT`) | Yes | EST_ALLOTMENT starts issuing instead of 422 |
| Remove a city or template | Yes for uniqueness | Requests for it fail; its sequences stay in Postgres, so re-adding it continues, never restarts |
| Change a counter's `start` after it has issued | No effect | The sequence already exists; `CREATE SEQUENCE IF NOT EXISTS` keeps the old start |
| Change an existing template's `format` | **Not safe in general** | If the counter key changes (e.g. `[CITY]` or a period removed, or the counter renamed), a new sequence starts from `start` and can re-issue IDs already printed under the old format. Give the new format a new template ID instead |

**Consequences**
- **Restart to apply:** any change to `data/` needs every instance restarted. Compose mounts `./data:/data:ro`, so it is a restart, not a rebuild (`docker compose restart backend`).
- **Short-lived inconsistency during a rolling restart:** for a few seconds some instances know the new city and others answer 404. Uniqueness is unaffected; it just looks inconsistent.
- **All or nothing:** a bad row stops startup with one clear line naming the file and line, so an instance never runs on half a configuration.
- **No runtime validation of format edits:** the service cannot tell that a template's format changed since the last run, so the unsafe row above relies on the operator.

**With another day: reload without a restart.**
- **Reload in place:** an admin endpoint (or a file watcher) re-reads both files, validates them completely, and swaps the registries in one step. The registries already hold their maps in `volatile` fields and are replaced whole, so requests see either the old or the new configuration, never a mix. A failed validation keeps the old one and reports why.
- **Guard format changes:** keep the format each template ID was first seen with (a small `template_versions` table) and refuse a reload that changes the counter key of an existing template ID, pointing the operator to a new template ID.
- **Every instance at once:** publish a reload event (Postgres `LISTEN/NOTIFY` is enough) so all replicas switch together instead of one by one.
- **At platform scale:** cities and templates belong in a master-data service with an admin API and change events (DIGIT's MDMS plays this role), not in files inside the ID generator (ADR-003).
