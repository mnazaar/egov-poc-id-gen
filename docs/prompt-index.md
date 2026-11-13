# Prompt index

A readable guide to the author's prompts in the Claude Code session. The session itself is exported **unedited** in [claude-code-session.txt](claude-code-session.txt): prompts were typed quickly during the time box, so they are short and contain typos. Each row below summarises what the author directed and cites the line numbers in that file, so every summary can be checked against the original wording.

| Phase | Lines | What the author directed |
|---|---|---|
| **Start from the plan** | 2 | Shared the architecture and implementation plan, written and reviewed in Claude beforehand, and asked for the Quarkus scaffold (step 1) |
| **Review the plan's defaults** | 570, 595, 742 | Questioned the plan's Java 21, reviewed the trade-offs, chose Java 25 (latest LTS), and had the change applied in the code, the Docker images and the plan |
| **Organise the working documents** | 742, 937, 1110 | AI log and decision notes kept in `docs/` and updated after every step; a private checklist for the submission email (decisions, AI log, architecture, how the plan came first) |
| **Record decisions as ADRs** | 1194, 1391, 1691 | ADR-004 on Nginx versus an API gateway; every ADR in its own file; the Nginx configuration documented in ADR-004 |
| **Make it testable by hand** | 1919, 1974 | Manual test steps, added to the README |
| **Plan the submission evidence** | 2148, 2191, 2233, 2414 | Email to include the README and repo links, the Postman collection and JMeter results, and the top 3 open questions with assumptions; JMeter added to the plan |
| **Repository and accounts** | 2463–2621 | Checked the git identity; fixed pushing so it uses the account that owns the repo |
| **Step 2: config and templates** | 2659 | Parser, renderer, counter keys, registries and unit tests, with an explicit done-check |
| **Keep control of history** | 3470 | Required that nothing is committed before the author's review; made it a standing rule |
| **Check against the brief** | 3535 | Asked for the work to be checked against the original brief and data README |
| **Verify hands-on** | 3618–4131 | Connected a database client, looked for the sequences, ran step 2 manually, restarted services; tested a broken `cities.csv` and challenged the stack trace it produced (3854), which led to a one-line startup error |
| **Step 3: allocation and API** | 4151, 4226 | Sequence store, block allocator, issue service, endpoints, error mapper and API tests, with an explicit done-check |
| **Probe the design** | 4909, 4952 | Asked whether adding a city works without code changes; had restart-to-reload recorded as ADR-005, with hot reload as the "another day" item |
| **Tidy the plan** | 5212 | ADRs linked from the plan rather than repeated in it |
| **Step 4: concurrency and load** | 5379, 5976, 6211 | Concurrency test and JMeter plan with an explicit done-check; accepted the suggestion to return JSON when no backend is up; committed the test output as evidence |
| **Decide on caching** | 6244 | Decided against Redis: local number blocks avoid a network hop per ID, interleaved IDs are acceptable, a shared recent view is "another day"; recorded as ADR-006 |
| **Step 5: UI** | 6564, 6882, 7030, 7190 | The web UI; the author's Postman collection listed with the required documents; a UI test walkthrough in the README; checked whether the UI really reached all three instances |
| **Release readiness** | 7275–7390 | Cleaned the Docker environment to test a fresh clone; published the image as `nazaarblue/idgen` for amd64 and arm64 |
| **Wording and documents** | 7741–8380 | Renamed the plan "Architecture and Implementation Plan" throughout; checked the brief's submission checklist; had `spec.md`, `plan.md` and `ai-log.md` written, with the PDF, Postman, load test and Docker Hub evidence listed and commit links in the plan |
| **Final checks** | 8582, 8624 | Asked what remained before sending; had the repo and image checked as public from an anonymous client |

The author asked for commits and pushes at lines 2483, 4157, 5189, 6211, 6546, 7246 and 8515. Once, Claude committed a finished step without being asked; the author had it undone (line 3470), and from then on nothing was committed before review.
