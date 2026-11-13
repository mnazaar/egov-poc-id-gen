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

**4. Failover when a backend stops**
```sh
docker stop idgen-backend-2
curl -i http://localhost:8080/api/ping     # still 200, now 2 instances
docker start idgen-backend-2                   # rejoins within ~5 s
```

**5. Readiness goes DOWN without Postgres**
```sh
docker stop idgen-postgres-1
curl http://localhost:8080/q/health/ready  # 503, DOWN
curl http://localhost:8080/api/ping        # still 200 (no DB needed)
docker start idgen-postgres-1
```

**6. Only Nginx is exposed**
```sh
curl http://localhost:5432                 # connection refused
docker compose port backend 8080               # error: no host port
```

**Logs:** `docker compose logs -f nginx backend`
**Tear down:** `docker compose down` (add `-v` to wipe Postgres).
