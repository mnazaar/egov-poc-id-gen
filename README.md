# egov-poc-id-gen

ID generation service: formatted government IDs from templates, for 10 city tenants, with zero duplicates under parallel load.
Quarkus + Postgres sequences. Design: [docs/architecture.md](docs/architecture.md).

## Run

```sh
docker compose up --build
```

Then open http://localhost:8080 (`/api/ping`, `/q/health/ready`).
The first build downloads Maven dependencies and takes a few minutes.

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
Repeat the first call: each of the 3 instances takes its own block of 100, so you'll see `…-000001`, `…-000101`, `…-000201`, then `…-000002`. Unique, with gaps by design (ADR-001).

**6. Errors have one shape**
```sh
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{"templateId":"EST_ALLOTMENT","city":"AMR"}'   # 422 COUNTER_NOT_CONFIGURED
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{"templateId":"NOPE","city":"AMR"}'            # 404 UNKNOWN_TEMPLATE
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{"templateId":"PG_RECEIPT","city":"XYZ"}'      # 404 UNKNOWN_CITY
curl -X POST http://localhost:8080/api/ids/bulk -H "Content-Type: application/json" -d '{"templateId":"PG_RECEIPT","city":"AMR","count":0}'  # 400 INVALID_COUNT
curl -X POST http://localhost:8080/api/ids -H "Content-Type: application/json" -d '{bad'                                          # 400 VALIDATION_ERROR
```
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
