# closeauth-integration-tests

Black-box integration tests for the CloseAuth backend. They boot the **real** backend image (built from
`closeauth-backend/Dockerfile`) networked with real **Postgres**, **Redis**, and **Mailpit** via
[Testcontainers](https://testcontainers.com/), then drive the app over HTTP exactly as a client would and verify both
the API responses **and** the actual side effects (database rows, captured emails).

> **This module has ZERO dependency on `closeauth-backend`'s Java classpath.** It knows the backend only as an HTTP
> service and a Postgres schema it can read. It does not import backend classes, entities, or DTOs. That separation is
> the point — do not add `closeauth-backend` as a Maven dependency.

## Prerequisites

- **Docker** running (Testcontainers needs it). If Docker is absent, the suite **skips** (it does not fail).
- **Java 21** and **Maven** (same Java as the backend).
- The **backend boot jar must be built first**, because `closeauth-backend/Dockerfile` is jar-based
  (`COPY target/*.jar`). If the jar is missing the suite skips with a message telling you to build it.

## How to run

From the **repository root**:

```bash
# One shot: package the backend (produces the jar) and run the integration suite.
mvn -pl closeauth-backend,closeauth-integration-tests package
```

or, in two steps:

```bash
mvn -pl closeauth-backend -am package -DskipTests    # build the backend boot jar
mvn -pl closeauth-integration-tests test             # run the integration suite (uses the jar to build the app image)
```

First run pulls images (`postgres:17-alpine`, `redis:8-alpine`, `axllent/mailpit`) and builds the backend image, so it
is slower; later runs reuse the Docker layer cache. No other manual setup is required beyond Docker.

> **Docker API compatibility:** Docker Engine 29+ dropped support for API versions below 1.40, and the docker-java
> client bundled with Testcontainers otherwise defaults to 1.32 (every call then 400s with *"client version 1.32 is
> too old"*). `src/test/resources/docker-java.properties` pins `api.version=1.44` (read from the classpath by
> docker-java) to fix this with no manual setup. If a future daemon raises its minimum API above 1.44, bump that value.

Nothing else needs to be started by hand — the app container runs Flyway migrations itself on boot (as in normal
operation), so there is no separate migration step.

## What the harness wires up

`support/CloseAuthStack` is the reusable orchestration (a JVM-wide singleton — started once, shared by every test):

| Container | Image | In-network alias | Notes |
|---|---|---|---|
| Postgres | `postgres:17-alpine` | `postgres` | matches `docker-compose.yml`; the app migrates it via Flyway on boot |
| Redis | `redis:8-alpine` | `redis` | matches `docker-compose.yml` |
| Mailpit | `axllent/mailpit` | `mailpit` | SMTP `1025` + REST API `8025`; captures the app's outbound email |
| Backend | built from `closeauth-backend/Dockerfile` | `closeauth-backend` | `docker` profile, port `9000`, context path `/closeauth` |

The app container is configured to reach the others by their **network aliases** (not `localhost`):
`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/closeauth`, `SPRING_DATA_REDIS_HOST=redis`, `SMTP_HOST=mailpit`
`SMTP_PORT=1025`, with `SMTP_AUTH=false` / `SMTP_STARTTLS=false` (Mailpit rejects auth/STARTTLS by default), and a
bootstrap platform-admin credential injected via `JAVA_OPTS` (`-Dcloseauth.platform-admin.bootstrap-*`). Readiness is an
HTTP wait on `GET /closeauth/actuator/health`.

## Conventions every future test class MUST follow

- **Extend `support.IntegrationTest`.** It boots the stack, points REST Assured at the app (`baseURI` + `/closeauth`
  context path), skips gracefully when Docker/jar are absent, and gives you `mailpit()`, `db()`, and
  `platformAdminToken()`.
- **Isolate via unique fixture data, not a clean database.** Containers are shared across test methods (booting is
  slow), so **never assume an empty DB between methods**. Create a fresh tenant / user / client per test using
  `support.Fixtures` (`slug(...)`, `email(...)`, `clientId(...)` — each carries a random suffix). Because every `mvn`
  run gets fresh containers, a second run is never polluted by the first.
- **Wait for emails, don't sleep.** Use `mailpit().waitForMessageTo(email, timeout)` — a bounded poll — then extract
  codes/links from the message body. No backdoor access to secrets: the code comes out of the real email.
- **Assert side effects, not just status codes.** Use `db().queryOne("select ... where ...", args)` for a direct,
  read-only JDBC check that the DB actually changed. Keep it read-only — this module never mutates the database.
- **Name tests as journeys.** Class `*JourneyTest`, method reads as a user story
  (e.g. `registersVerifiesAndLogsIn`), under `com.anterka.closeauth.it.journeys`.

## Layout

```
src/test/java/com/anterka/closeauth/it/
  support/
    CloseAuthStack.java   – Testcontainers orchestration (Postgres+Redis+Mailpit+app), JVM singleton
    IntegrationTest.java  – base class: boots the stack, configures REST Assured, exposes helpers
    MailpitClient.java    – Mailpit REST client + waitForMessageTo(...) polling helper
    MailpitMessage.java   – captured-email view (recipients, subject, text)
    Db.java               – minimal read-only JDBC query helper
    Fixtures.java         – unique emails / slugs / client ids (test isolation)
  journeys/
    EmailVerifiedRegistrationJourneyTest.java  – IT-1's one proven journey
```

## Scope (IT-1)

One journey only — chosen to exercise every piece of plumbing (image build + boot, HTTP, Mailpit capture + code
extraction, JDBC assertion, clean teardown/isolation): **EMAIL_VERIFIED self-registration → read the emailed code →
verify → login → assert `ACTIVE`/`email_verified` in Postgres.** Broader feature coverage comes in later stages. There
is no CI wiring yet (a later stage).
