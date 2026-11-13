# egov-poc-id-gen

ID generation service: formatted government IDs from templates, for 10 city tenants, with zero duplicates under parallel load.
Quarkus + Postgres sequences. Design: [docs/architecture.md](docs/architecture.md).

## Run

```sh
docker compose up --build
```

Then open http://localhost:8080 (`/api/ping`, `/q/health/ready`).
The first build downloads Maven dependencies and takes a few minutes.

## How numbering behaves (by design)

- **Unique, not sequential.** Each of the 3 backend instances takes its own block of numbers from a Postgres sequence (100 at a time, 10 for narrow counters) and issues from memory. Requests are spread round-robin, so consecutive IDs come from different blocks: `PG-AMR-2026-27-000001`, `…000101`, `…000201`, `…000002`. Within one instance they count up; across instances they interleave. Use `issuedAt`, not the ID, for issue order.
- **Gaps are expected.** A restarted instance abandons the rest of its block. The brief allows gaps; it never allows duplicates, and blocks never overlap.
- **No Redis on purpose.** A shared counter would add a network round trip to every ID; local blocks need one Postgres call per 100 IDs (817 req/s at p95 56 ms below).
- **"Recent IDs" is per instance.** `GET /api/ids/recent` shows what the instance that answers has issued, so a refresh can show another instance's list, and a restart empties it. A shared view (Redis, written asynchronously so issuing isn't slowed) is the "another day" item.

Details and alternatives: [ADR-001](docs/adr/0001-sequences-in-memory-blocks.md), [ADR-006](docs/adr/0006-per-instance-memory-no-redis.md).

## Concurrency test (zero duplicates)

```sh
docker compose up --build -d                       # if not already running
docker compose --profile test run --rm loadtest    # 50 processes, 30 s
```

[`loadtest/loadtest.py`](loadtest/loadtest.py) starts **50 genuinely parallel OS processes** (`multiprocessing`, not threads). Each loops for 30 s over random templates and cities (tenant IDs, codes, mixed case), sending single requests and bulk requests of 1–50 through Nginx to all 3 backends. It collects every ID returned and **exits 1 if any ID appears twice**. EST_ALLOTMENT is included on purpose: it must keep returning 422 under load. Options: `-e DURATION=60 -e WORKERS=100`. A JSON summary is written to `loadtest/out/`.

Result on a laptop (3 backends, Postgres 16):

```
Requests         16,035  (515.9 req/s over 31.1s)
IDs issued      110,176  (3,544.9 IDs/s)
Distinct IDs    110,176
DUPLICATES            0
HTTP status   {'200': 13356, '422': 2679}
Errors        {'422 COUNTER_NOT_CONFIGURED': 2679}
By prefix     {'PGR': 22344, 'WS': 22217, 'TL': 21953, 'BPA': 21913, 'PG': 21749}
Instances     {'172.18.0.4:8080': 4459, '172.18.0.3:8080': 4452, '172.18.0.5:8080': 4445}

PASS: 110,176 IDs, 0 duplicates
```

**With every backend restarted mid-run.** Start a longer run, then restart the backends from a second terminal while it runs:

```sh
docker compose --profile test run --rm -e DURATION=45 loadtest
docker compose restart backend                     # second terminal, ~10 s in
```

```
Requests         19,378  (413.3 req/s over 46.9s)
IDs issued       28,151  (600.4 IDs/s)
Distinct IDs     28,151
DUPLICATES            0
HTTP status   {'200': 3541, '422': 707, '503': 15130}
Errors        {'503 SERVICE_UNAVAILABLE': 15130, '422 COUNTER_NOT_CONFIGURED': 707}

PASS: 28,151 IDs, 0 duplicates
```

While all 3 backends were down (about 24 s), Nginx answered `503 SERVICE_UNAVAILABLE` in the API's JSON error shape, with `Retry-After: 5` (ADR-004); the 50 workers kept retrying, hence the count. After the restart each backend takes fresh blocks from the Postgres sequences, so nothing issued before the restart is issued again (ADR-001).

## JMeter throughput

```sh
docker compose --profile jmeter run --rm jmeter    # 50 users, 60 s; HTML report in loadtest/out/jmeter/report/
```

[`loadtest/idgen.jmx`](loadtest/idgen.jmx) mixes single and bulk requests over every template × city from [`loadtest/jmeter/requests.csv`](loadtest/jmeter/requests.csv) (regenerate with `python loadtest/jmeter/make_requests.py`). Each response must be HTTP 200 with an ID matching its template's pattern. The image downloads Apache JMeter 5.6.3 from the Apache archive and checks its SHA-512. Overrides: append `-Jusers=100 -Jduration=120` to the jmeter arguments, or run a local JMeter with `-Jhost=localhost`. JMeter measures speed; `loadtest.py` is the duplicate proof, because it keeps every ID.

Result on a laptop (3 backends):

| Request | Samples | Errors | Throughput | Mean | p90 | p95 | p99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| single | 24,459 | 0.00 % | 408.9 req/s | 17.6 ms | 40 ms | 52 ms | 80 ms | 170 ms |
| bulk (1–50 IDs) | 24,452 | 0.00 % | 408.3 req/s | 20.8 ms | 46 ms | 58 ms | 83 ms | 190 ms |
| **total** | **48,911** | **0.00 %** | **816.8 req/s** | 19.2 ms | 43 ms | 56 ms | 84 ms | 190 ms |

## Manual testing

Run these from the repo folder with the stack up. On Windows PowerShell, type `curl.exe` instead of `curl` (plain `curl` there is an alias for `Invoke-WebRequest`).

**1. Stack is up and healthy**
```sh
docker compose up --build -d
docker compose ps
```
`postgres` and `backend-1..3` show `(healthy)`; `nginx` is `Up` on `0.0.0.0:8080`.

**2. Requests rotate across replicas**
```sh
curl -i http://localhost:8080/api/ping
```
Repeat a few times: `instance` in the body and the `X-Upstream` header cycle through 3 values.

**3. Readiness includes Postgres**
```sh
curl http://localhost:8080/q/health/ready
```
Expect `"status": "UP"` with the PostgreSQL check UP.

**4. Templates and cities are loaded**
```sh
curl http://localhost:8080/api/templates   # 6 templates; EST_ALLOTMENT has "canIssue": false
curl http://localhost:8080/api/cities      # 10 cities
```

**5. Issue IDs**
```sh
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json"      -d '{"templateId":"PG_RECEIPT","city":"pb.amritsar"}'
curl -X POST http://localhost:8080/api/ids/bulk -H "Content-Type: application/json"      -d '{"templateId":"WS_CONNECTION","city":"LDH","count":3}'
curl "http://localhost:8080/api/ids/recent?templateId=PG_RECEIPT&city=AMR&limit=10"
```
Repeat the first call: each of the 3 instances takes its own block of 100, so you'll see `…-000001`, `…-000101`, `…-000201`, then `…-000002`. Unique, with gaps by design (see [How numbering behaves](#how-numbering-behaves-by-design)). The recent list can also change between calls: it is per instance.

**6. Errors have one shape**
```sh
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{"templateId":"EST_ALLOTMENT","city":"AMR"}'   # 422 COUNTER_NOT_CONFIGURED
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{"templateId":"NOPE","city":"AMR"}'            # 404 UNKNOWN_TEMPLATE
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{"templateId":"PG_RECEIPT","city":"XYZ"}'      # 404 UNKNOWN_CITY
curl -X POST http://localhost:8080/api/ids/bulk -H "Content-Type: application/json" -d '{"templateId":"PG_RECEIPT","city":"AMR","count":0}'  # 400 INVALID_COUNT
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{bad'                                          # 400 VALIDATION_ERROR
```
With every backend stopped (`docker compose stop backend`), Nginx answers `503 SERVICE_UNAVAILABLE` in the same shape.
Each returns `{"error":{"code":"…","message":"…","details":{…}}}`.

**7. Sequences in Postgres**
```sh
docker compose exec postgres psql -U idgen -d idgen -c "select sequencename, increment_by, last_value from pg_sequences order by 1"
```
41 sequences: 4 templates × 10 cities + one global `seq_pgr_complaint`; `seq_ws_*` use blocks of 10.

**8. Failover when a backend stops**
```sh
docker stop idgen-backend-2
curl -i http://localhost:8080/api/ping     # still 200, now 2 instances
docker start idgen-backend-2                   # rejoins within ~5 s
```

**9. Postgres down**
```sh
docker stop idgen-postgres-1
curl http://localhost:8080/q/health/ready  # 503, DOWN
curl http://localhost:8080/api/ping        # still 200 (no DB needed)
curl -X POST http://localhost:8080/api/ids/bulk -H "Content-Type: application/json" -d '{"templateId":"TL_LICENSE","city":"NGP","count":500}'  # 503 SEQUENCE_UNAVAILABLE
docker start idgen-postgres-1
```

**10. Only Nginx is exposed**
```sh
curl http://localhost:5432                 # connection refused
docker compose port backend 8080               # error: no host port
```

**Logs:** `docker compose logs -f nginx backend`
**Tear down:** `docker compose down` (add `-v` to wipe Postgres).
