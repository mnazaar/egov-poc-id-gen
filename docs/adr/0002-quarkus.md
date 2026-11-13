## ADR-002: Build the service on Quarkus (JVM now, native later)

**Decision:** the backend is a Quarkus app, run in JVM mode for this submission. Native image is the first item for "another day". Accepted, 13 Nov 2026.

**Context.** The ID service is stateless apart from its in-memory blocks (ADR-001), so it scales by adding pods. How useful that is depends on how fast a new pod is ready to serve: slow startup means a traffic spike is over before the new pods arrive. Quarkus does most framework wiring at build time rather than at startup, so it starts fast and uses little memory.

**Why JVM mode for the ****3****-hour POC**
- Startup is typically about a second in JVM mode. Millisecond startup comes from **native** mode, which is roughly tens of ms (approximate, from Quarkus's published figures).
- A native build takes several minutes and needs several GB of RAM for the GraalVM/Mandrel builder. If that happened inside `docker compose up` on a reviewer's laptop, the build itself would be the biggest risk.
- The code is the same either way, so going native later is a build flag (`-Dnative`), not a rewrite.

**Shape of the app**
- **REST:** `quarkus-rest` with `POST /api/ids` (single) and `POST /api/ids/bulk`, plus one exception mapper for the error format.
- **Postgres:** `quarkus-reactive-pg-client` (or Agroal JDBC) calling only `CREATE SEQUENCE` at startup and `nextval` on refill. No Hibernate/ORM, since there are no tables.
- **Blocks:** `ConcurrentHashMap<String, Block>` with one lock per key. Single issues take the next value; bulk issues take a range in one go.
- **Config:** `templates.json` and `cities.csv` are read once at startup (`@Startup`). A config error fails startup with a clear message.
- **UI:** a static HTML/JS page served from `META-INF/resources`, so there's no separate frontend container.
- **Health:** `quarkus-smallrye-health` (`/q/health/ready`) feeds the compose healthcheck and, later, the Kubernetes readiness probe.

**Consequences**
- **Scaling interacts with ADR-001:** every new pod takes fresh blocks, and every pod removed abandons its unused ones. A sequence can't take numbers back, so frequent scale-up and scale-down means more gaps. Uniqueness is unaffected; larger gaps is the cost of elasticity, and it's within the brief.
- **Pod readiness is more than startup time:** image pull, scheduling and the first DB connection add seconds. Native shrinks the app's share of that, and a small native image also pulls faster.
- **Reviewers need nothing extra:** the Dockerfile does a multi-stage Maven build, so `docker compose up` is still the only command. The first build downloads dependencies and takes a few minutes; say so in the README.

**With another day: prove elastic scaling with native**
1. Build the native image (`./mvnw package -Dnative -Dquarkus.native.container-build=true`) and record its startup time and RSS against JVM mode.
1. Deploy to a local Kubernetes cluster (kind/k3d) with a HorizontalPodAutoscaler on CPU or request rate.
1. Run the concurrency test as a ramping load: scale from 1 to N pods and back during the run.
1. Report the time from scale-out decision to a ready pod, and that the full ID set still has zero duplicates across every pod that came and went.
