## ADR-004: Nginx as the load balancer, not an API gateway

**Decision:** a plain Nginx reverse proxy is the only public entry point. It round-robins across the backend replicas and does nothing else. No API gateway (Kong, Spring Cloud Gateway, a cloud API gateway). Accepted, 13 Nov 2026.

**Context.** The POC needs exactly two things in front of the backend: one public port, so reviewers' requests spread over 3 instances (ADR-001's multi-instance proof), and a way to keep serving while one instance restarts. What an API gateway adds (authentication, API keys, per-tenant rate limits, request transformation, a developer portal) is all a non-goal here: there is no auth in scope, and the API is two `POST`s and three `GET`s.

| Option | What it brings | Cost here | Verdict |
|---|---|---|---|
| Nginx reverse proxy (chosen) | Round-robin, failover to the next instance, re-resolving replica addresses | One container, about 20 lines of config | Does exactly the job, nothing to learn or configure |
| Kong / Tyk | Auth, rate limits, keys, plugins | Its own DB or declarative config; plugins we would not turn on | Right once auth arrives, not before |
| Spring Cloud Gateway | Routing and filters in Java | A second JVM app to build, test and start | More code than the service it fronts |
| Traefik | Discovers containers by Docker labels | Mounts the Docker socket into a public container | Works, but adds a security trade-off for no gain |
| Envoy | Rich load balancing, active health checks | Verbose config, harder to read in a review | Overkill for 3 replicas |
| Cloud API gateway (AWS, Azure, GCP) | Managed auth, quotas, TLS | Can't run in `docker compose up` on a reviewer's laptop | Breaks the one-command rule |

**Consequences**
- **No edge protection:** any caller can issue IDs for any tenant, with no rate limit. Acceptable because authentication is out of scope (ADR-003 already lists it as "another day").
- **Passive health only:** open-source Nginx removes an instance only after a request to it fails, then retries on the next one. A `POST` that already reached an instance is not retried, so a retry never issues IDs twice by accident (it would only burn numbers, but staying predictable matters more).
- **Re-resolution gap:** when replicas are recreated with new addresses, Nginx can return 502 for up to 5 seconds until it re-resolves `backend`. A plain restart keeps the address and is not affected.
- **Nothing in the app depends on Nginx.** Replacing it with a gateway or a Kubernetes Ingress later is a deployment change, not a code change.

**Configuration** (`nginx/nginx.conf`, mounted read-only into `nginx:alpine`):

```nginx
# Docker's DNS returns one A record per backend replica; re-resolve every 5s
# so restarted/scaled replicas are picked up.
resolver 127.0.0.11 valid=5s ipv6=off;

upstream idgen_backend {
    zone idgen_backend 64k;          # shared memory, required for "resolve"
    server backend:8080 resolve;     # one entry per replica, round-robin
}

server {
    listen 8080;

    location / {
        proxy_pass http://idgen_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        add_header X-Upstream $upstream_addr always;   # which replica answered

        # If an instance is down, try the next one. A POST that already reached
        # an instance is never retried ("non_idempotent" is deliberately absent).
        proxy_next_upstream error timeout http_502 http_503;
        proxy_connect_timeout 2s;
    }
}
```

- **`resolver` + `resolve`:** Docker's DNS returns one address per replica, and Nginx re-checks every 5 s, so a recreated or scaled replica joins the pool without restarting Nginx.
- **`upstream`:** round-robin by default, so consecutive requests land on different replicas (the `X-Upstream` header shows which).
- **`proxy_next_upstream`:** a request that can't reach an instance, or gets 502/503 from one, moves to the next. A `POST` that was already sent is never retried, so a retry can't silently burn or double-issue numbers.
- **Port 8080** is the only one published on the host; Postgres and the backends stay internal.

**With another day: put a real gateway in front once tenants authenticate.**
- In production this service would sit behind the platform's existing gateway, which already does authentication and routing for the other city services.
- The gateway verifies the caller's token and passes the tenant as a trusted header or claim; the ID service stops trusting `city` from the request body (ADR-003).
- Add per-tenant rate limits at the gateway, so one city cannot burn through a narrow counter such as `SEQ_WS:4`.
- In Kubernetes, Nginx is replaced by a Service plus an Ingress (or the gateway itself), with readiness from `/q/health/ready` (ADR-002).
