# JurisTrack

Employee time tracking and productive-hours management for Juris LPO. Employees start the workday, take breaks, resume and end the day; productive and
break time are calculated from the recorded intervals. A manager sees the live board, history and reports of the employees who report to them; the owner (admin) adds employees, assigns their managers and manages corrections, reports and the audit log.

> **Status: migration in progress.** This repository is the backend (Spring Boot API, database, jobs). The web app is the React app in its own repository,
> `juris-track-frontend`. The earlier Next.js + Prisma implementation is in this repository's git history (`feature/juristrack-time-tracking`).
> See [references/MIGRATION_PLAN.md](references/MIGRATION_PLAN.md) for the inventory, decisions and order of work.

## Stack

| Part | Technology | Port (dev) |
|---|---|---|
| Frontend | React single-page app (Vite, TypeScript) in `juris-track-frontend`; proxies `/api/v1/*` to the API | 3006 |
| Backend | Java 17, Spring Boot 3, Flyway, Maven Wrapper — in `backend/` | 8081 |
| Database | PostgreSQL 16 in docker-compose | 5437 on the host |
| Tests | JUnit 5 + Testcontainers (backend); Vitest and Playwright in the frontend repo | |

## Environments

One file per environment, `backend/src/main/resources/application-<env>.properties`, on top of `application.properties`.

| Env | Web / API / DB ports | Database | Sign-in code |
|---|---|---|---|
| `dev` | 3006 / 8081 / 5437 | `juristrack` | fixed `000000` |
| `sqe` | 3017 / 8092 / 5447 | `juristrack_sqe` | random, emailed |
| `uat` | 3027 / 8102 / 5457 | `juristrack_uat` | random, emailed |
| `prd` | 3010 / 8210 / 5467 | `juristrack_prd` | random, emailed |

Secrets of sqe/uat/prd are `${ENV_VARS}` set on the server (never committed). E2E tests use web 3008 / API 8083.

## Quick start (dev)

```sh
docker compose up -d db                       # Postgres 16 on 127.0.0.1:5437 (container juristrack-backend-db)
cd backend
./mvnw spring-boot:run                        # API on http://localhost:8081 (profile dev), Flyway migrates on start
curl http://localhost:8081/actuator/health    # {"status":"UP",...}
./mvnw test                                   # JUnit 5 + Testcontainers (Docker must be running)
```

One-shot commands: `./mvnw spring-boot:run -Dspring-boot.run.arguments=<command>` with `migrate`, `seed` or `reset`
(`reset` empties the database and refuses unless `juristrack.app-url` is localhost or `juristrack.allow-database-reset=true`).
There is **no default Spring profile**: `spring-boot:run` runs as `dev` (set in the Maven plugin), but a packaged jar (`java -jar`, Docker, a server) must be started
with `SPRING_PROFILES_ACTIVE=dev|sqe|uat|prd`, else it refuses to start, so a forgotten setting can never run with the dev shortcuts (fixed code `000000`, public secrets).
In sqe, uat and prd set `TRUSTED_PROXIES` to the web server / gateway address: only a request from there may name the client in `X-Forwarded-For`.
The API in Docker too: `docker compose up -d --build backend`. Backups: `scripts/backup.sh` / `scripts/restore.sh` (keep 14 days).

## Working on it

See [CLAUDE.md](CLAUDE.md) and [INSTRUCTIONS.md](INSTRUCTIONS.md) (naming and REST standards, git rules, checks to run before "done").
