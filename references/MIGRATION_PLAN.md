# JurisTrack — Spring Boot migration plan (inventory, decisions, order of work)

## Decisions taken (owner, answered after the inventory)

| # | Decision |
|---|---|
| D1 | JurisTrack is sold standalone and also embedded in JurisOne (it is not built inside JurisOne). Standalone: own database, own emailed-code sign-in. Embedded: JurisOne login, JurisOne backend as gateway, signed JWT in the `Authorization` header. See `INTEGRATION.md`. Nothing is changed in the JurisOne repos until the owner says so. |
| D1b | "Same users": JurisOne employees become JurisTrack users **just in time** from the token claims (matched by `jurisone_employee_id`, then email). An optional employee push endpoint can come later. No database import (this replaces the earlier `import-employees` idea). |
| D13 | Roles: `SUPER_ADMIN` (platform) -> `ADMIN` (business owner, standalone) -> `MANAGER` (JurisTrack manager) -> `EMPLOYEE`. No HR/Finance roles. Everyone has the same personal tracking section; a manager also gets a Team tab. Embedded in JurisOne every employee can use JurisTrack and only manager / not manager matters. See `INTEGRATION.md` section 3b. |
| D14 | The reporting line replaces teams: `users.manager_id`; a manager sees their reports (direct reports by default); workdays keep a snapshot of the manager at the time. JurisOne pushes the roster (`PUT /api/integration/employees`) and sends `managerId` in the token. (Teams and membership history of the old app are dropped; owner can overrule, see `INTEGRATION.md` section 5.) |
| D15 | A manager sees direct reports by default; a switch includes the people one level further down (indirect). Depth is a parameter. |
| D16 | Managers can correct their reports' time in both modes (reason of 5+ characters, overlap/future checks, audit entry), never their own. |
| D17 | Embedded: one role, the JurisTrack manager, granted in JurisOne and sent as `trackRole=MANAGER` on every token. "Individual" mode = standalone company. Teams are dropped; tests run on fresh data. |
| D2 | Sign-in by emailed code (like JurisOne). No passwords. |
| D3 | Fresh start; no data is migrated from the old database. |
| D4 | Multi-business like JurisOne (`business_id` + row-level security). |
| D5 | Start after End Day reopens today's workday (the gap counts as neither productive nor break). |
| D7 | UI uses the JurisOne design CSS, not Tailwind. |
| D8 | English + Hindi. |
| D9 | Ports as in the README. |
| D12 | Backend = this repo (`juris-track`), new branch from `dev`; the web app = new repo `juris-track-frontend`; the Next.js code is converted to Java, the old code stays in git history. |

Open (defaults used until the owner says otherwise): D6 display zone as a per-browser cookie in the frontend, D10 scope (keep everything except Next.js-specific parts; demo seed as the `seed` command), D11 names (`com.jurislpo.juristrack`, artifact `juristrack-backend`, cookies `jt_at` / `jt_rt`).

---

# Phase 0 inventory (read-only)

Output of Phase 0 of `MIGRATION-GUIDE-BACKEND.md` / `MIGRATION-GUIDE-FRONTEND.md`. Source: `C:\JurisLPO\JurisTrack`, branch
`feature/juristrack-time-tracking` @ `679d9e5` (remote `amitjurislpo/juris-track`, branches `main`, `dev`). Nothing in JurisTrack was changed.
When the new backend repo exists (Phase 1) this file becomes `references/MIGRATION_PLAN.md` there. It is not committed anywhere yet.
Not run in this phase: `npm run test:flows`, the app itself (no behaviour was verified, only code was read).

## 0. Summary

| Item | Count | Where |
|---|---|---|
| Server actions | 20 functions in 8 files | section 2 |
| Route handlers (JSON polling + one CSV) | 4 | section 2 |
| Pages | about 24 (employee 3 + profile, manager 6, admin 11, login, setup, root, not-found) | section 3 |
| Page data reads (no API today, each becomes a GET endpoint) | about 14 service reads used by pages | section 3 |
| Prisma models | 9 (User, Session, Team, TeamMembership, Workday, WorkSession, BreakSession, AuditLog, OrganizationSetting) | section 4 |
| Enums | 2 (`Role`, `WorkdayStatus`) | section 4 |
| Migrations | 3 (init, integrity CHECK constraints, display timezones) | section 4 |
| Background jobs | 0 (stale workdays are flagged at read time, nothing is scheduled) | section 6 |
| File storage / email / PDF | none / none / none | section 6 |
| Automated tests | none (no Vitest, no Playwright); one script `scripts/verify-flows.ts` with 23 checks against a real DB | section 8 |
| Code size | about 8,650 lines of TS/TSX: components 32 files, services 9, lib 20 | |

The app is small and has a clear core: **an employee time tracker** (start / break / resume / end day) with teams, managers, reports, an admin audit log
and display time zones. Stack today: Next.js 16 (App Router, Server Components + Server Actions), React 19.3, Tailwind 4, Prisma 7 with the `pg` adapter,
Zod 4, bcryptjs, PostgreSQL 17 (docker-compose, host port 5432).

## 1. Auth, sessions, security (today)

- **Sign-in:** email + password (`loginAction`), bcrypt cost 12, constant-time guard for unknown emails. **No OTP.** JurisOne's decision is "emailed code only";
  see decision D2.
- **Sessions:** random 256-bit token in an httpOnly `SameSite=Lax` cookie (`jt_session`, `__Host-jt_session` in production, max-age 30 days); the DB stores only
  `HMAC-SHA256(token, AUTH_SECRET)` in table `Session`; sliding expiry `SESSION_TTL_HOURS` (default 12), touched at most every 15 minutes; revoked on
  logout, deactivation, password reset and password change (other devices).
- **Roles:** `ADMIN`, `MANAGER`, `EMPLOYEE` (a fixed enum, no permission table). Admin does not track time; managers and employees do (`TRACKED_ROLES`).
- **Authorization layers:** `proxy.ts` (cookie present), section layouts (role redirect), pages/actions (`authorize(...)`), services (`employeeScopeWhere`).
  A manager sees only employees whose **current** team they manage; out-of-scope records return 404.
- **CSRF:** Next's built-in Server Action Origin check; the 4 route handlers are GET only. **Rate limit:** in-process, 8 failures per email per 15 min.
- **PII encryption:** none. **First run:** `db:bootstrap` creates one admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD`; the admin then creates the organization on
  `/setup`, then teams and users; initial passwords are a default (`DEFAULT_USER_PASSWORD`), generated, or typed; `mustChangePassword` forces a change.
- **Errors:** `ActionResult = { ok:true, data, message? } | { ok:false, code, error, fieldErrors? }`, codes UNAUTHENTICATED 401, FORBIDDEN 403, NOT_FOUND 404,
  VALIDATION 422, CONFLICT 409, INVALID_STATE 409, RATE_LIMITED 429, INTERNAL 500. Note: **API error shape differs from JurisOne's** (`{ok,message?,data?}`
  success + RFC 7807 `application/problem+json` errors).

## 2. Mutations and route handlers (become REST endpoints)

Server actions (`src/app/actions/*.ts`), all return `ActionResult`:

| File | Action | Role | What it does | Proposed REST (JurisOne style) |
|---|---|---|---|---|
| auth.ts | `loginAction` | public | email+password, throttle, create session, redirect to role home | `POST /api/auth/sessions` (or OTP flow, D2) |
| auth.ts | `logoutAction` | any | destroy session | `DELETE /api/auth/sessions/current` |
| time.ts | `timeCommandAction(start|break|resume|end)` | EMPLOYEE, MANAGER | state machine on own workday, per-employee row lock, idempotent | `POST /api/time/commands` or `PUT /api/time/state` (design in Phase 2) |
| employees.ts | `createEmployeeAction` | ADMIN | create user (+ optional team), returns one-time password | `POST /api/employees` (201) |
| employees.ts | `updateEmployeeAction` | ADMIN | edit details/role | `PATCH /api/employees/{id}` |
| employees.ts | `setEmployeeActiveAction` | ADMIN | activate/deactivate (closes open workday, revokes sessions) | `PUT /api/employees/{id}/status` |
| employees.ts | `assignTeamAction` | ADMIN | move between teams (closes membership, opens new) | `PUT /api/employees/{id}/team` |
| employees.ts | `resetPasswordAction` | ADMIN | reset to default/generated password, sign out everywhere | `POST /api/employees/{id}/password-resets` |
| profile.ts | `changePasswordAction` | any | change own password, revoke other sessions | `PUT /api/profile/password` |
| teams.ts | `createTeamAction`, `updateTeamAction`, `setTeamActiveAction`, `deleteTeamAction` | ADMIN | team CRUD, manager assignment, activate, delete | `POST/PATCH/DELETE /api/teams[/{id}]`, `PUT /api/teams/{id}/status` |
| admin-time.ts | `adjustSessionAction` | ADMIN | edit a work/break interval (reason required, overlap check, recompute totals, audit) | `PATCH /api/time/sessions/{id}` |
| admin-time.ts | `closeWorkdayAction` | ADMIN | close a forgotten workday (reason required, audit) | `PUT /api/time/workdays/{id}/status` |
| admin-time.ts / organization.ts | `updateSettingsAction`, `createOrganizationAction` | ADMIN | organization name, time zone, stale hours, display zones; first-run create | `PUT /api/organization` |
| preferences.ts | `setDisplayTimezoneAction` | any | per-browser display zone, stored in cookie `jt_tz` | `PUT /api/me/preferences` (or keep as a frontend cookie, D6) |

Route handlers (`src/app/api/**`): `GET /api/me/status` (own state, polled every 30 s), `GET /api/board` (live board, filters date/teamId/employeeId/status, polled
every 15 s), `GET /api/employees/[id]/status`, `GET /api/reports/export` (CSV: UTF-8 BOM, CRLF, formula-injection guard, headers name the display zone). Unauthenticated
API calls return 401 here (JurisOne returns 403 for signed-out; its own standard says 401).

## 3. Pages (become React routes; each read becomes an endpoint)

| Area | Route | Data read today (service) | Role |
|---|---|---|---|
| Public | `/login` | none | public |
| Root | `/` | redirect to role home | any |
| Setup | `/setup` | `getSettings`, `setupDefaults` | ADMIN (first run) |
| Employee | `/dashboard` | `getEmployeeState`, `getWorkdayRecords`, `dailySeries`, `totalsOf` | EMPLOYEE, MANAGER |
| Employee | `/my-time` | `getEmployeeState` (timer panel, timeline) | EMPLOYEE, MANAGER |
| Employee | `/my-history` | `getWorkdayRecords`, `dailySeries`, `totalsOf`, `orgToday` | EMPLOYEE, MANAGER |
| Employee | `/profile` | own user, change-password form | any |
| Manager | `/manager` | `getBoard`, `scopedEmployeeOptions`, `listTeams` | MANAGER |
| Manager | `/manager/teams`, `/manager/teams/[id]` | teams in scope | MANAGER |
| Manager | `/manager/employees`, `/manager/employees/[id]` | employees in scope | MANAGER |
| Manager | `/manager/reports` | `getWorkdayRecords`, aggregates | MANAGER |
| Admin | `/admin` | `getBoard`, `getWorkdayRecords`, `dailySeries` | ADMIN |
| Admin | `/admin/teams`, `/admin/teams/[id]` | `listTeams`, `getTeam`, `listUserOptions` | ADMIN |
| Admin | `/admin/employees`, `/new`, `/[id]` | `listEmployees`, `getEmployee`, `listTeams` | ADMIN |
| Admin | `/admin/time-tracking` | `getBoard` (+ filters, corrections) | ADMIN |
| Admin | `/admin/reports` | `getWorkdayRecords`, `aggregate`, `totalsOf` | ADMIN |
| Admin | `/admin/audit-log` | `listAuditLogs` (filters, paging) | ADMIN |
| Admin | `/admin/settings` | `getSettings` | ADMIN |

Shared UI: app shell + sidebar nav per role (`lib/auth/roles.ts navFor`), display-timezone switcher (shows each zone's current time), toasts, modal/confirm
dialog, forms with field errors, tables, badges, daily chart, timeline, live board, timer panel (client timer measures the server/client clock offset, resyncs
after each action; polling 15 s board / 30 s timer, plus on focus/reconnect). Styling is **Tailwind 4 with its own tokens (light + dark)**, not the JurisOne CSS.

## 4. Data model (Prisma, `prisma/schema.prisma`, 216 lines)

| Model | Key fields / invariants |
|---|---|
| `User` | cuid id, optional unique `employeeCode`, names, unique `email`, `passwordHash`, `role`, `isActive`, `isDemo`, `mustChangePassword`, `lastLoginAt` |
| `Session` | id = HMAC of token, `userId` (cascade), `expiresAt`, `lastSeenAt`, `userAgent` |
| `Team` | `name` + unique lower-cased `nameKey`, optional `managerId`, `isActive` |
| `TeamMembership` | history; `activeEmployeeId` unique nullable = employee while current (at most one current team) |
| `Workday` | `employeeId`, snapshot `teamId`, `date` (calendar day in org zone at start), `timezone`, `startedAt/endedAt`, `status` (WORKING, ON_BREAK, COMPLETED), unique nullable `activeEmployeeId` (one open day), cached `productiveSeconds/breakSeconds/breakCount`, `@@unique([employeeId,date])` |
| `WorkSession`, `BreakSession` | intervals; unique nullable `openWorkdayId` (one open interval per day) |
| `AuditLog` | actor, subject, action, entityType/Id, `before`/`after` JSON, reason |
| `OrganizationSetting` | singleton `id=1`: name, `timezone`, `staleWorkdayHours` (1-48), `displayTimezones[]` |

Migrations: `init`, `integrity_checks` (hand-written **CHECK constraints** on all interval/open-key/total tables, singleton check), `display_timezones`.
Prisma enums are native PG types. `Date` columns use `@db.Date`. The "only one open X" rule uses nullable-unique keys plus the CHECKs, which map straight to
Flyway SQL + JPA. Per-employee serialization uses `SELECT ... FOR UPDATE` on the user row (`withEmployeeLock`).

## 5. Business logic to port to Java (pure engines, test 1:1 with golden fixtures)

| Module (TS) | Content |
|---|---|
| `lib/time/calc.ts` | interval seconds (open = now - start), sums, `computeTotals` (productive, break, span, breakCount) |
| `lib/time/tz.ts`, `zones.ts`, `ranges.ts` | date keys in a zone, zone offsets, `zonedTimeToUtc`, week/month starts, ranges (today/yesterday/week/month/custom, max 366 days), zone abbreviations |
| `lib/time/format.ts`, `live.ts` | clock/duration/decimal-hours formatting (stays in the frontend), live totals from a snapshot |
| `time-tracking.service.ts` | state machine NOT_STARTED -> WORKING <-> ON_BREAK -> DAY_ENDED, idempotency, concurrency, **reopen after end** (see section 7), snapshot/timeline segments |
| `time-corrections.service.ts` | adjust interval, close forgotten workday, overlap/future/ordering validation |
| `board.service.ts`, `reports.service.ts` | live board + metrics by team, workday records, `aggregate` by employee/team/date, `dailySeries`, totals |
| `employees.service.ts`, `teams.service.ts`, `scope.service.ts` | CRUD with audit in the same transaction, team moves, scope rules, deactivation closing the open day |
| `settings.service.ts`, `audit.service.ts` | org settings (`orgToday`), audit write/list/filter |
| `lib/validation/*` | Zod schemas (password rule: 10+ chars, upper+lower+digit) -> Bean Validation records |

## 6. Jobs, files, email, settings

- **Jobs:** none. "Not ended" (stale) workdays are computed at read time from `staleWorkdayHours`. Optional later: a scheduled job.
- **Files/PDF/email:** none. Temporary passwords are shown once to the admin (no email). JurisOne already has SMTP + outbox if emails are wanted.
- **Settings/env:** `DATABASE_URL`, `AUTH_SECRET`, `SESSION_TTL_HOURS`, `ORG_TIMEZONE`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `DEFAULT_USER_PASSWORD`,
  `SEED_DEMO_PASSWORD`, `POSTGRES_*`, `ALLOW_DATA_WIPE`. A real `.env` exists in the repo folder (git-ignored, **not read** in this phase). All of these become
  `application-<env>.properties` keys / `${ENV_VARS}`; the default-password setting is a security risk to replace.
- **Seed/ops:** `bootstrap.ts` (admin; `--wipe` deletes everything, refuses in production), `seed.ts` (demo: 3 teams, 12 employees, about 4 weeks of history, domain
  `demo.jurislpo.test`). Both become Spring commands (`seed`, `reset`) like JurisOne's.

## 7. Defects and mismatches found while reading

1. **Reopen-after-end vs docs and test.** Commit `679d9e5` made `start` after `end` reopen today's workday (gap counts as neither productive nor break), but the
   README ("cannot restart") and `verify-flows.ts` check 172 ("starting again the same day is rejected") still describe the old rule. The script's check very likely
   fails today (not run). Decide the intended rule (D5), then port with a test for it.
2. **Cookie max-age 30 days vs session TTL 12 h** is intentional (DB expiry wins), but the README says "sliding 12 h"; keep, document.
3. **In-process login throttle** only; JurisOne rate-limits per IP and per email (also in-memory).
4. **API error codes** differ from JurisOne's standard (see section 1); signed-out API calls return 401 here.
5. **Hard-coded names** `jt_session`, `jt_tz`, `Jt-xxxx` password prefix, "juristrack" in log text; fine, but rename consistently.
6. `AGENTS.md` carries a generated Next.js agent block ("This is NOT the Next.js you know"); it goes away with Next.js.
7. No tests besides the flows script, so the Java side will define the first real test suite (use verify-flows' 23 checks as the starting list).

## 8. Existing checks to carry over

`scripts/verify-flows.ts` (23 checks, needs a real DB): calculation examples (8 h, 8.5 h), full start/break/resume/end flow, productive time frozen during break,
rejected invalid transitions and idempotent repeats, 10 parallel starts/breaks/resumes -> exactly one record, independent timers per employee, manager/employee
scope, non-admin cannot adjust time, overlap rejected, audited recompute, deactivation closes the open workday. The README also lists a 28-check manual browser
suite (login/route protection, double-click-safe flow, live board polling, scoped CSV export, admin CRUD, mobile layout). Both become JUnit 5 integration tests and
Playwright specs.

## 9. What already exists in JurisOne for JurisTrack

- JurisOne has a **Juris Track module placeholder**: nav item `juris-track` (`Nav.JURIS_TRACK`), `GET /api/juris-track` (404 unless the business's plan or a grant includes
  the module), and a frontend page that says "Coming soon". Businesses and subscriptions are already modelled (multi-business, row-level security).
- JurisOne also has employees, roles/permissions, departments, holidays, shifts, attendance (punches, regularization), org time zones, audit log, notifications,
  email outbox, sign-in by emailed code, argon2/JWT sessions. JurisTrack's users/teams/roles/audit/settings overlap with these.

## 10. Decisions needed (please answer before Phase 1)

| # | Question | My default |
|---|---|---|
| D1 | **Architecture:** (a) JurisTrack as its **own product**: new repos `juris-track` (backend) and `juris-track-frontend`, own database, own login; or (b) a **module inside JurisOne** (the placeholder), reusing its users, roles, departments, subscription gating and audit, with new tables for workdays/sessions only. You asked for repos `juris-track` and `juris-track-frontend`, which means (a). Note the repo `amitjurislpo/juris-track` already exists on GitHub and holds the current Next.js code. | **(a)** as you said, but say if JurisTrack users must be the same people as JurisOne employees |
| D2 | **Sign-in:** keep email + password (with forced first-login change), or switch to JurisOne's emailed 6-digit code (needs SMTP; admin no longer hands out passwords)? | Emailed code, to match JurisOne, if SMTP is available; otherwise keep password |
| D3 | **Existing data:** is there data in the current JurisTrack database to keep (workdays, users), or start fresh (demo only)? | Fresh schema (V1 from the Prisma schema + CHECK constraints), no data migration |
| D4 | **Single organization or multi-business?** Today one organization. JurisOne is multi-business with row-level security. | Single organization (as today); no tenancy code |
| D5 | **Reopen after End Day:** keep the new behaviour (start again reopens the day, the gap counts as neither productive nor break), or the documented old rule (cannot restart)? | Keep the reopen behaviour (latest commit), update docs/test |
| D6 | **Display time zone preference:** keep it as a per-browser cookie in the frontend (no endpoint), or store it per user? | Per-browser frontend cookie |
| D7 | **Design:** keep JurisTrack's Tailwind look, or restyle with the JurisOne design (the People prototype CSS verbatim)? The frontend guide assumes verbatim prototype CSS; JurisTrack has no prototype in `references/` (only `timetrack.txt`, the product brief). | Port the current Tailwind design faithfully (no prototype exists), i.e. plain CSS equivalent; or Tailwind if you prefer |
| D8 | **Languages:** English only, or English + Hindi like JurisOne? | English only |
| D9 | **Ports and environments:** JurisOne uses web/API/DB 3005/8080/5436 (dev), 3015/8090/5446 (sqe), 3025/8100/5456 (uat), 3000/8200/5466 (prd); e2e 3006/8081. JurisTrack today: web 3000, DB 5432. Proposed dev set: web 3007, API 8082, DB 5437, e2e 3008/8083; sqe/uat/prd offset the same way (3017/8092/5447, 3027/8102/5457, 3010/8210/5467). | Use the proposed ports |
| D10 | **Scope cleanup:** drop anything not needed (e.g. demo seed `isDemo`, `db:reset-data` wipe, `employeeCode` optional)? Keep reports CSV export and audit log (yes). | Keep everything except the Next.js-specific parts; port the demo seed as the `seed` command with `seed.demo` |
| D11 | **Package name and Java base package** (`com.jurislpo.juristrack`), Maven artifact `juristrack-backend`, database `juristrack` (+ `_sqe/_uat/_prd`), cookie names (`jt_at`/`jt_rt` like JurisOne's `jo_at`/`jo_rt`). | As written |
| D12 | **Git:** create the two new repos under `amitjurislpo`; branch `feature/...` from `dev`; PRs to `dev`. Should the existing `juris-track` repo be the backend (replacing the Next.js code on a new branch) or a fresh empty repo? | Reuse the `juris-track` repo for the backend on a new branch; create `juris-track-frontend` fresh. Old Next.js code stays reachable in git history |

## 11. Proposed order after the decisions (as in the guides)

Phase 1 repos + docs -> Phase 2 skeleton (Flyway V1 incl. CHECKs, entities, health, Docker) -> Phase 3 pure engines (time calc, zones, ranges) with JUnit + golden
fixtures from the Node code -> Phase 4 auth + sessions + audit + settings -> Phase 5 modules: (1) organization + users + teams, (2) time tracking state machine +
board + employee state, (3) corrections, (4) reports + CSV, (5) audit log page -> Phase 6 seed/reset commands -> Phase 7 cleanup, naming sweep, parity checklist.
Frontend follows each backend module: shell + login, then employee pages, manager pages, admin pages.
