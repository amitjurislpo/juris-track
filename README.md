# JurisTrack

Employee time tracking and productive-hours management for **Juris LPO**.

Employees start their workday, take breaks, resume, and end the day. Productive time and break time are **calculated from recorded work and break intervals stored in PostgreSQL**. The on-screen timer only displays those timestamps and never stores its own count. Managers see their teams live, and administrators manage teams, employees, corrections and organization settings.

---

## Tech stack

| Layer | Choice |
|---|---|
| Framework | Next.js 16 (App Router, Server Components, Server Actions, Turbopack) |
| Language | TypeScript (strict, `noUncheckedIndexedAccess`) |
| UI | Tailwind CSS 4, design tokens with light and dark themes, no component library |
| Database | PostgreSQL 17 |
| ORM | Prisma 7 with the `pg` driver adapter |
| Validation | Zod 4 |
| Auth | Custom database-backed sessions, with bcrypt password hashing |

Runtime dependencies are kept small: `next`, `react`, `@prisma/client`, `@prisma/adapter-pg`, `pg`, `zod`, `bcryptjs`.

---

## Quick start

Prerequisites: Node.js 20+ and Docker (or any PostgreSQL 14+ instance).

```bash
cp .env.example .env              # then set AUTH_SECRET (see below)
npm install                       # also runs `prisma generate`
npm run db:up                     # starts PostgreSQL via docker-compose
npm run db:deploy                 # applies migrations to a fresh database
npm run db:seed                   # DEMO data (wipes the database)
npm run dev                       # http://localhost:3000
```

Generate a secret:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
```

Production:

```bash
npm run db:deploy && npm run build && npm start
```

### Scripts

| Script | Purpose |
|---|---|
| `npm run dev` / `build` / `start` | Next.js |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run lint` | ESLint (Next core-web-vitals + TypeScript) |
| `npm run db:validate` | Validate the Prisma schema |
| `npm run db:migrate` | Create/apply migrations in development |
| `npm run db:deploy` | Apply migrations (CI and production) |
| `npm run db:seed` | Insert demo data (**destructive**; refuses to run when `NODE_ENV=production`) |
| `npm run db:reset` | Drop, migrate and seed |
| `npm run test:flows` | End-to-end verification of the time engine against the database |

### Environment variables

| Variable | Required | Description |
|---|---|---|
| `DATABASE_URL` | yes | PostgreSQL connection string |
| `AUTH_SECRET` | yes | 32 or more characters. Used to HMAC session tokens before storage |
| `SESSION_TTL_HOURS` | no (12) | Sliding session lifetime |
| `ORG_TIMEZONE` | no (`Asia/Kolkata`) | Initial organization timezone. After that it is managed in **Admin → Settings** |
| `SEED_DEMO_PASSWORD` | no (`Demo@12345`) | Password for seeded demo accounts |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | docker only | Credentials for the local `docker-compose` database |

### Demo accounts (development only)

All seeded accounts use the reserved `demo.jurislpo.test` domain and are flagged `isDemo` (shown with a **Demo** badge). The password is `SEED_DEMO_PASSWORD`.

| Role | Email |
|---|---|
| Admin | `admin@demo.jurislpo.test` |
| Manager, Paralegal | `manager.paralegal@demo.jurislpo.test` |
| Manager, Sales | `manager.sales@demo.jurislpo.test` |
| Manager, Technology | `manager.tech@demo.jurislpo.test` |
| Employees | `priya.sharma@…` (working), `rahul.verma@…` (on break), `arjun.nair@…` (not started), `rohan.gupta@…` (forgot to end yesterday), and more. The seed prints the full list |

The seed creates 3 teams and 12 employees in mixed live states, with about 4 weeks of completed weekday history. "Today's" states are anchored to the moment the seed runs, so nothing is dated in the future.

---

## How time is calculated

```
Productive = Σ (work session end − start)      open session → now − start
Break      = Σ (break session end − start)     open break   → now − start
Span       = workday end − workday start
```

- **Source of truth:** `WorkSession` and `BreakSession` rows with `startedAt` / `endedAt` timestamps. Nothing increments a counter.
- **Completed workdays** cache `productiveSeconds`, `breakSeconds` and `breakCount` for fast reporting. These are recomputed from the sessions on completion and after every correction, and are never written from client input.
- **The browser timer** receives a snapshot (closed totals, plus the `startedAt` of any open interval and the server time). It measures the server/client clock offset and recomputes from the clock every second. It re-syncs after each action, every 30 s, when the tab becomes visible, and when the network reconnects. Refreshing, sleeping, switching devices or having a wrong client clock never changes the recorded time.

## Time-tracking integrity

- **State machine:** `NOT_STARTED → WORKING ⇄ ON_BREAK`, then `WORKING → DAY_ENDED`. Every transition is validated on the server.
- **Per-employee row lock:** each transition runs in a transaction that starts with `SELECT … FOR UPDATE` on the employee row. Concurrent requests for the same employee are serialized, while other employees are never blocked. Timestamps come from the **database clock**, so multiple app instances agree on "now".
- **Idempotency:** repeating a command that is already applied (double-click, retry, a second tab) returns the current state and creates nothing. Invalid commands, such as resuming when not on break, are rejected.
- **Database guarantees**, independent of application code:
  - Nullable unique "open keys" allow at most one open workday per employee, one open work session and one open break per workday, and one current team per employee.
  - `@@unique([employeeId, date])` allows one workday per employee per date.
  - `CHECK` constraints enforce `endedAt ≥ startedAt`, a consistent open/closed state, and non-negative totals.
- **Policies:**
  - A break must be ended with **Back to Work** before **End Workday**. The End button is disabled and the server rejects the request.
  - A workday belongs to the calendar date, in the organization timezone, on which it **started**. It may run past midnight, so night shifts are supported.
  - Workdays open longer than the configured threshold (default 14 h) are flagged **Not ended** for administrator review.

## Display timezones (India / Eastern)

Every user can choose which timezone times are **displayed** in, using **Show times in** at the bottom of the sidebar. Each option shows that zone's current time. The default list is India (`Asia/Kolkata`, IST) and US Eastern (`America/New_York`, which shows EDT or EST automatically depending on the date). Administrators can add or remove zones in **Admin → Settings → Display timezones**. The organization timezone is always offered.

- **Per browser:** the choice is stored in a cookie, so one person switching doesn't affect anyone else.
- **What changes:** start/end times, timelines, last activity, the audit log, admin correction forms (times are entered in the selected zone) and CSV export (headers name the zone, for example `Start (EDT)`).
- **What does not change:** durations, stored data, and the calendar day a workday belongs to. Dates and date filters stay on the organization timezone, so reports cover the same records whichever zone you view them in.

## Roles and permissions

| | Employee | Manager | Admin |
|---|---|---|---|
| Track own time | ✓ | ✓ (via **My Time**) | – |
| See own history | ✓ | ✓ | – |
| Live board, employee details, reports | – | Employees whose **current** team they manage | Everyone |
| Manage teams and employees, corrections, settings, audit log | – | – | ✓ |

Authorization is enforced on the server at several layers:
1. **Proxy:** a fast check for a session cookie.
2. **Section layouts:** a role check that redirects before anything streams.
3. **Pages and actions:** each checks again.
4. **Services:** every query is scoped by role (`employeeScopeWhere`).

Out-of-scope records return **404**, so their existence is not revealed.

## Auditability

Every administrative change is written to `AuditLog` in the same transaction as the change itself. The entry records who made it, when, the before and after values, and the reason. This covers:

- employee created, updated, role changed, moved between teams, deactivated or reactivated
- password reset
- team created, changed, manager reassigned, deactivated or deleted
- **time adjustments** and **administrator-closed workdays** (a reason is mandatory)
- settings changes

Employees cannot edit time. Administrators can adjust an interval's times or close a forgotten workday. Overlaps, future times and intervals that end before they start are rejected.

## Routes

| Area | Routes |
|---|---|
| Public | `/login` |
| Employee | `/dashboard`, `/my-time`, `/my-history`, `/profile` |
| Manager | `/manager`, `/manager/teams`, `/manager/teams/[id]`, `/manager/employees`, `/manager/employees/[id]`, `/manager/reports` |
| Admin | `/admin`, `/admin/teams`, `/admin/teams/[id]`, `/admin/employees`, `/admin/employees/new`, `/admin/employees/[id]`, `/admin/time-tracking`, `/admin/reports`, `/admin/audit-log`, `/admin/settings` |
| API | `GET /api/me/status`, `GET /api/board`, `GET /api/employees/[id]/status`, `GET /api/reports/export` (CSV) |

All mutations are Server Actions in `src/app/actions/`. They get built-in Origin checking against CSRF, SameSite=Lax cookies, Zod validation, and a consistent `ActionResult` envelope.

## Project structure

```
prisma/
  schema.prisma            data model (documented inline)
  migrations/              init + integrity CHECK constraints
  seed.ts                  demo data
scripts/verify-flows.ts    engine verification (npm run test:flows)
src/
  proxy.ts                 optimistic route protection
  app/
    actions/               server actions (auth, time, employees, teams, admin-time, profile)
    api/                   JSON/CSV route handlers for polling and export
    login/                 public sign-in
    (app)/                 authenticated shell
      (tracker)/           employee time pages (EMPLOYEE, MANAGER)
      manager/             manager section (MANAGER)
      admin/               admin section (ADMIN)
  components/              ui/ primitives, time/ (timer, timeline, chart), board/, views/, admin/
  lib/                     auth, db, env, errors, validation, time (tz, calc, format, ranges)
  server/services/         business logic and data access (the only layer that queries the DB)
```

## Verification performed

- `npm run typecheck`, `npm run lint`, `npm run build` and `npm run db:validate` all pass.
- `npm run test:flows` (23 checks, real PostgreSQL) covers:
  - the specification's calculation examples (8 h and 8.5 h)
  - the full start / break / resume / end flow, including productive time frozen during a break
  - rejected invalid transitions and idempotent repeats
  - 10 parallel starts, breaks and resumes creating exactly one record each
  - independent timers, manager and employee scope
  - audited corrections, overlap rejection, and deactivation closing an open workday
- A browser suite (28 checks in Microsoft Edge) covered:
  - login and route protection, including a forged cookie
  - the double-click-safe employee flow, with timer continuity across refresh and devices
  - manager live visibility, including polling-driven status changes and 404 for out-of-scope records
  - scoped CSV export
  - admin team and employee creation, duplicate-name and duplicate-email errors, team moves with history
  - deactivation, stale-workday closure, and mobile layout without horizontal scroll

## Assumptions and limitations

- **Polling rather than WebSockets:** the manager board every 15 s, the employee timer every 30 s, plus on focus and reconnect. Timers tick locally between syncs.
- **One workday per employee per calendar date.** An employee who ends the day by mistake cannot restart it. An administrator can adjust the recorded interval times, but there is no "reopen workday" action.
- **One manager per team.** A manager's scope is based on current team membership. Historical records follow the employee's current team for access purposes, while reports attribute each workday to the team at the time it was recorded.
- **Login throttling is in-process.** Use a shared limiter, such as at the reverse proxy, for multi-instance deployments.
- **Temporary passwords are shown once** to the administrator. There is no email delivery.
- **Organization timezone changes** apply to new workdays. Existing workdays keep the date and timezone they were recorded with. The display-timezone switcher never changes stored data.
- **Administrators** do not track their own time. Give them a Manager account if needed.
- **`npm audit`** reports advisories in the Prisma CLI's dev-tooling dependency chain (`deepmerge-ts`), not in runtime code.
