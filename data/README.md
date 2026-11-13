# Data files

- **`cities.csv`** — the city master: `tenant_id`, `city_code`, `city_name`. 10 cities.
- **`templates.json`** — 6 ID templates (`id`, `name`, `format`) plus a `counters` section configuring **named** counters (e.g., starting value). Plain `[SEQ:n]` placeholders use an implicit counter scoped per (template, city) and need no entry in `counters`.
Placeholder grammar is specified in `BRIEF.md`. Load both files at startup; treat them as the system's configuration, not as user input to be edited.
