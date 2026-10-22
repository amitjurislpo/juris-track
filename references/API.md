# JurisTrack API

The contract between this backend and its clients (the `juris-track-frontend` web app and, embedded, the JurisOne gateway). Keep it exactly in
sync with the code: an endpoint, parameter or field is added, renamed or removed here in the same change as in the controller.

## Conventions (every endpoint)

- **Base path** `/api`. JSON in and out (`Content-Type: application/json`).
- **Authentication**: standalone, the `jt_at` / `jt_rt` cookies set by `POST /api/auth/sessions`; embedded in JurisOne, `Authorization: Bearer <JurisOne token>`
  (see `INTEGRATION.md`). Both produce the same signed-in user and the same rules.
- **Origin check**: a cookie-authenticated `POST`, `PUT`, `PATCH` or `DELETE` must carry `Origin` equal to the web app's URL, else 403 `CROSS_SITE_BLOCKED`.
  Bearer requests are exempt.
- **Success body**: `{ "ok": true, "message"?: string, "data"?: ... }`. Empty `message` / `data` are left out; `null` inside `data` is kept.
- **Errors**: `application/problem+json` (RFC 7807): `{ type, title, status, detail, instance, code, errors? }`. `detail` is the message to show,
  `code` is stable for programs, `errors` = `[{ field, message }]` on 400 validation errors (the first one is `detail`).
- **Common codes**: 400 `VALIDATION_FAILED` (also malformed JSON, a malformed id in the path or a wrong type), 401 `UNAUTHENTICATED` / `TOKEN_EXPIRED`
  (refresh the session and retry), 403 `FORBIDDEN` (signed in, wrong role), 403 `BUSINESS_SUSPENDED` (the user's business is suspended; signing out still
  works), 403 `CROSS_SITE_BLOCKED`, 404 `NOT_FOUND` (also a record of another business), 409 `CONFLICT` (a duplicate the specific codes below do not
  cover), 422 business rule (specific codes below), 429 `RATE_LIMITED`, 500 `INTERNAL_ERROR`.
- **Types**: ids are UUID strings; timestamps are ISO-8601 UTC strings (`2026-10-22T09:30:00.123456Z`); time zones are IANA ids; roles are `SUPER_ADMIN`,
  `ADMIN` (the business owner), `MANAGER`, `EMPLOYEE`.
- **Lists** (`GET` on a collection): query parameters `search` (text, cut to 100 characters), `sort` (one of the endpoint's keys), `sortDirection`
  (`asc` default | `desc`), `page` (from 1, default 1), `pageSize` (1 to 100, default 25) plus the endpoint's filters. An unknown value is 400 with the
  parameter as `field`. Answer: `data = { rows: [...], total, page, pageSize }` (`total` = rows on all pages).
- **PATCH**: a field that is left out stays as it is; a field sent as `null` is cleared where that is allowed (else 400). `PUT .../status` is idempotent:
  setting the state a record already has changes nothing and writes no audit entry.
- **Audit**: every write below is written to the audit log (actor, action, subject, record before/after, reason) in the same transaction.

Roles column: who may call it; everybody else signed in gets 403 `FORBIDDEN`, signed out 401.

## Endpoint list

| Method | Path | Roles | Purpose |
|---|---|---|---|
| GET | `/actuator/health` | public | Health (`{"status":"UP"}`) |
| POST | `/api/auth/otp-requests` | public | Email a sign-in code |
| POST | `/api/auth/sessions` | public | Sign in with the code |
| POST | `/api/auth/session-refreshes` | public (refresh cookie) | Renew the session |
| DELETE | `/api/auth/sessions/current` | public | Sign out |
| GET | `/api/me` | any signed-in user | Who is signed in |
| GET | `/api/businesses` | SUPER_ADMIN | List businesses |
| POST | `/api/businesses` | SUPER_ADMIN | Create a business and its owner |
| GET | `/api/businesses/{businessId}` | SUPER_ADMIN | One business |
| PATCH | `/api/businesses/{businessId}` | SUPER_ADMIN | Change a business's settings |
| PUT | `/api/businesses/{businessId}/status` | SUPER_ADMIN | Suspend / reactivate |
| GET | `/api/business` | ADMIN | The owner's business settings |
| PATCH | `/api/business` | ADMIN | Change the owner's business settings |
| GET | `/api/employees` | ADMIN | List the business's people |
| POST | `/api/employees` | ADMIN | Add an employee |
| GET | `/api/employees/{employeeId}` | ADMIN | One employee |
| PATCH | `/api/employees/{employeeId}` | ADMIN | Change an employee |
| PUT | `/api/employees/{employeeId}/status` | ADMIN | Deactivate / reactivate |
| GET | `/api/me/time` | ADMIN, MANAGER, EMPLOYEE | My current time state (timer, today's timeline) |
| POST | `/api/me/time-events` | ADMIN, MANAGER, EMPLOYEE | Start work, break, resume, end the day |
| GET | `/api/workdays/mine` | ADMIN, MANAGER, EMPLOYEE | My history: workdays of a date range, totals, daily series |
| GET | `/api/workdays/{workdayId}` | ADMIN, MANAGER, EMPLOYEE | One of my workdays with its work and break intervals |

## Sign-in (standalone)

### POST /api/auth/otp-requests
Body `{ email: string }`. Always 200 `{ ok, message: "If that account exists and is active, a 6-digit code is on its way to the email." }`; no code is sent
to an unknown or deactivated account or to a user of a suspended business. 400 missing/invalid email; 429 too many requests (per IP and per email).

### POST /api/auth/sessions
Body `{ email: string, otp: string (6 digits), remember?: boolean }` (`remember` = keep me signed in). 201 with the `jt_at` / `jt_rt` cookies and
`data = { id, firstName, lastName, email, role, businessName }` (`businessName` null for the SUPER_ADMIN). 400 malformed code; 401 wrong, expired or used
code, unknown or deactivated account, or suspended business (always the same message); 429 after too many failures from one IP.

### POST /api/auth/session-refreshes
No body; uses the `jt_rt` cookie. 201 with new cookies. 401 (cookies cleared) when the session ended, expired, the user was deactivated or the refresh
token was used twice.

### DELETE /api/auth/sessions/current
No body. Always 200; ends this device's session and clears the cookies.

## GET /api/me
Any signed-in user. `data = { id, firstName, lastName, email, role, businessName, employeeCode, managerName, businessTimeZone }`.
`businessName` and `businessTimeZone` are null for the SUPER_ADMIN; `employeeCode` and `managerName` ("First Last" of the reporting manager) are null when
not set. 401 signed out, 403 `BUSINESS_SUSPENDED`.

## Businesses (platform super admin)

The SUPER_ADMIN belongs to no business; these requests see every business. A business's **owner** is its earliest-created ADMIN.

### GET /api/businesses
Query: list parameters, `sort` = `name` (default) | `createdAt` | `employeeCount` | `status` (active first when ascending), `status` = `all` (default) |
`active` | `suspended`. `search` matches the business name, the owner's name and the owner's email.
Row: `{ id, name, timeZone, isSuspended, isLinkedToJurisone, ownerId, ownerName, ownerEmail, employeeCount, activeEmployeeCount, createdAt }`
(`owner*` null when the business has no ADMIN, possible for a JurisOne business; `employeeCount` counts every user of the business, owner included).

### POST /api/businesses
Body:
```json
{ "name": "string, 1-80", "timeZone": "IANA id, optional (default Asia/Kolkata)",
  "owner": { "firstName": "1-60", "lastName": "1-60", "email": "valid, at most 200", "employeeCode": "optional, letters/digits/-/_, at most 32" } }
```
Creates the business (stale workday hours 14, display time zones `Asia/Kolkata`, `America/New_York`) and its owner (role ADMIN, active, no manager) in one
transaction. 201 `{ ok, message: "Business created.", data: { id, ownerId } }`. The owner signs in with an emailed code.
Errors: 400 (fields `name`, `timeZone`, `owner.firstName`, `owner.lastName`, `owner.email`, `owner.employeeCode`), 409 `EMAIL_IN_USE` (the email belongs to
any user of any business or to the super admin; compared lower-cased). Audit: `business.created` and `employee.created` (the owner).

### GET /api/businesses/{businessId}
`data = { id, name, timeZone, staleWorkdayHours, displayTimeZones: string[], isSuspended, isLinkedToJurisone, createdAt, updatedAt,
owner: { id, employeeCode, firstName, lastName, email, isActive, lastLoginAt } | null, employeeCount, activeEmployeeCount }`. 404 unknown.

### PATCH /api/businesses/{businessId}
Body (each optional): `{ name: string 1-80, timeZone: string, staleWorkdayHours: integer 1-48, displayTimeZones: string[] (at most 8) }`. None may be
`null` (send `[]` for no display zones). Time zones are checked against the IANA database (any case, links such as `Asia/Calcutta`, three-letter ids such
as `IST`, offsets such as `+05:30`) and stored in canonical form; duplicate display zones are dropped. A new time zone applies to workdays started from
then on. 200 `{ ok, message: "Business saved.", data: <as GET /api/businesses/{businessId}> }`. 400 (field names as above, `displayTimeZones[i]` for one
zone), 404. Audit: `business.updated` (only when something changed).

### PUT /api/businesses/{businessId}/status
Body `{ isSuspended: boolean (required), reason?: string (at most 500) }`. 200 `{ ok, message: "Business suspended." | "Business is active.", data: <detail> }`.
While suspended the business's users get no sign-in codes, cannot sign in, and every request of theirs with an existing session (or JurisOne token) answers
403 `BUSINESS_SUSPENDED`; sessions are not ended, so they work again after reactivation. 400 (`isSuspended`, `reason`), 404.
Audit: `business.suspended` / `business.reactivated` with the reason.

## Business settings (owner)

### GET /api/business
ADMIN. The owner's own business: `data = { id, name, timeZone, staleWorkdayHours, displayTimeZones, createdAt, updatedAt }`. `displayTimeZones` is stored as
is; the business time zone is always offered for display as well (the client adds it).

### PATCH /api/business
ADMIN. Body and rules as `PATCH /api/businesses/{businessId}`. 200 `{ ok, message: "Settings saved.", data: <as GET /api/business> }`. 400. Audit:
`business.updated`.

## Employees (owner)

Every user of the owner's business, the owner included (never the SUPER_ADMIN). A user of another business answers 404 everywhere.

Employee (rows of the list and the single employee):
`{ id, employeeCode, firstName, lastName, email, role, isActive, managerId, managerName, lastLoginAt, createdAt, activeReportCount }`
(`managerName` = "First Last" of the manager, `lastLoginAt` null before the first sign-in, `activeReportCount` = active users whose manager this is).

### GET /api/employees
Query: list parameters, `sort` = `name` (default; first then last name) | `employeeCode` (empty codes last) | `email` | `role` (ADMIN, MANAGER, EMPLOYEE
when ascending) | `status` (active first when ascending) | `lastLoginAt` (never signed in last); filters `status` = `active` (default) | `inactive` | `all`,
`role` = one or more of `EMPLOYEE`, `MANAGER`, `ADMIN` comma-separated (any case; e.g. `role=MANAGER,ADMIN` for a manager picker), `managerId` = only the
direct reports of that user. `search` matches first name, last name, "first last", email and employee code (`%` and `_` are literal).

### POST /api/employees
Body: `{ firstName: 1-60, lastName: 1-60, email: valid, at most 200, employeeCode?: letters/digits/-/_, at most 32, role?: "EMPLOYEE" (default) | "MANAGER",
managerId?: uuid }`. The employee is active at once and signs in with an emailed code. 201 `{ ok, message: "Employee added.", data: { id } }`.
Errors: 400 (fields `firstName`, `lastName`, `email`, `employeeCode`; an unknown role or malformed `managerId` is a 400 without field), 409 `EMAIL_IN_USE`
(any business), 409 `EMPLOYEE_CODE_IN_USE` (this business), 422 `ROLE_NOT_ASSIGNABLE` (role ADMIN or SUPER_ADMIN), 422 manager rules (below).
Audit: `employee.created`.

### GET /api/employees/{employeeId}
`data = <employee>`. 404.

### PATCH /api/employees/{employeeId}
Body (each optional): `{ employeeCode: string | null, firstName: string, lastName: string, email: string, role: "EMPLOYEE" | "MANAGER", managerId: uuid | null }`.
`null` clears `employeeCode` or `managerId`; `null` for a name, the email or the role is a 400. 200 `{ ok, message: "Employee saved.", data: <employee> }`.
Errors: 400, 404, 409 `EMAIL_IN_USE`, 409 `EMPLOYEE_CODE_IN_USE`, 422 `ROLE_NOT_ASSIGNABLE` (giving ADMIN / SUPER_ADMIN, or changing an ADMIN's role),
422 `MANAGER_HAS_REPORTS` (a MANAGER with active reports cannot become an EMPLOYEE: give the reports another manager first), 422 manager rules.
Audit: `employee.updated` with the record before and after (only when something changed). A role change applies on the user's next request.

**Manager rules** (create and edit, whenever `managerId` is set to a new value): 422 `MANAGER_NOT_FOUND` (unknown, or a user of another business),
`MANAGER_INACTIVE` (deactivated), `MANAGER_ROLE_REQUIRED` (the manager's role is EMPLOYEE; it must be MANAGER or ADMIN), `MANAGER_IS_SELF`,
`MANAGER_CYCLE` (the chosen manager reports, directly or further down, to this employee).

### PUT /api/employees/{employeeId}/status
Body `{ isActive: boolean (required), reason?: string (at most 500) }`. 200 `{ ok, message: "Employee deactivated." | "Employee is active.", data: <employee> }`.
Deactivating ends every session of the person at once (their next request is 401) and they get no new sign-in codes until reactivated. Their reports keep
them as manager. In the same transaction their open workday (if any) is closed at that moment: the running work or break interval ends, the day is
completed and its totals are recomputed. Errors: 400 (`isActive`, `reason`), 404, 422 `CANNOT_DEACTIVATE_SELF`, 422 `LAST_ACTIVE_OWNER` (the business must
keep one active ADMIN). Audit: `employee.deactivated` (after includes `endedSessions`, and `closedWorkdayId` when a workday was closed) /
`employee.reactivated`, with the reason; a closed workday also writes `workday.closed` (before `{ status, endedAt: null }`, after
`{ status: "COMPLETED", endedAt }`, the reason given, else "Employee deactivated.").

## Time tracking (own)

Every user of a business (ADMIN, MANAGER, EMPLOYEE) tracks their own time; the SUPER_ADMIN gets 403 `FORBIDDEN` on every endpoint below. The employee is
always the signed-in user (an id in the body is ignored). A suspended business answers 403 `BUSINESS_SUSPENDED`, a deactivated user is signed out (401; 403 with a JurisOne token).
All times are UTC instants; dates (`YYYY-MM-DD`) are calendar dates in the business time zone; durations are whole seconds computed from the recorded
intervals (closed: end - start; running: now - start). Labels and formatting are the client's.

**States**: `NOT_STARTED` -> `START` -> `WORKING` <-> (`BREAK` / `RESUME`) `ON_BREAK`; `WORKING` -> `END` -> `DAY_ENDED`. A workday belongs to the date on
which it started in the business time zone and may run past midnight (a night shift keeps its start date). One workday per person and date. `START` after
the day ended on the same date **reopens** that workday with a new work session; the gap between the end and the new start counts as neither productive
nor break time (it stays inside `spanSeconds`). A break must be ended with `RESUME` before `END`.

**Workday** (snapshot; `GET /api/me/time` as `workday`, `GET /api/workdays/{workdayId}` as `data`):
`{ id, date, status: "WORKING" | "ON_BREAK" | "DAY_ENDED", startedAt, endedAt | null, isStale, closedProductiveSeconds, closedBreakSeconds,
activeWorkStartedAt | null, activeBreakStartedAt | null, productiveSeconds, breakSeconds, spanSeconds, breakCount,
lastActivity: { type: "STARTED_WORK" | "STARTED_BREAK" | "RESUMED_WORK" | "ENDED_DAY", at }, segments: [{ id, type: "WORK" | "BREAK", startedAt, endedAt | null }] }`.
`productiveSeconds` / `breakSeconds` include the running interval up to `serverTime`; a client keeps a timer running with
`closedProductiveSeconds + (now - activeWorkStartedAt)` and `closedBreakSeconds + (now - activeBreakStartedAt)`. `spanSeconds` = start of the day to its end
(or now). `breakCount` counts the running break too. `isStale` ("not ended") = the day is still open and `spanSeconds` is more than the business's
`staleWorkdayHours` (computed when read). `segments` are oldest first.

### GET /api/me/time
`data = { employeeId, serverTime, today, businessTimeZone, staleWorkdayHours, displayTimeZones: string[], status: "NOT_STARTED" | "WORKING" | "ON_BREAK" |
"DAY_ENDED", workday | null }`. `displayTimeZones` is the business's configured list (IANA ids, as stored; see `GET /api/business`) for every signed-in
user's time-zone switcher; the business time zone is always offered as well (the client adds it). It is returned only here and in the `state` of
`POST /api/me/time-events`; the history and workday endpoints do not repeat it.
`workday` is the open workday (whatever its date), else today's ended workday, else null (`status` NOT_STARTED). `serverTime` lets the client measure its
clock offset. After a night shift ended past midnight, today has no workday yet: the state is NOT_STARTED again.

### POST /api/me/time-events
Body `{ type: "START" | "BREAK" | "RESUME" | "END" }` (any case). Answer `data = { type, alreadyApplied, workdayId, state: <as GET /api/me/time> }`:
- **201** when the event changed the state, with `message` "Workday started. Have a productive day." / "Break started. Your productive timer is paused." /
  "Welcome back. Your productive timer is running." / "Workday ended. Your summary is ready." (`alreadyApplied` false);
- **200** without `message` when the person already is where the event leads (a double click, a retry, another device): nothing is created or changed
  and `alreadyApplied` is true. Repeats: `START` while working or on a break, `BREAK` while on a break, `RESUME` while working, `END` after the day ended.

`workdayId` = the workday the event applied to (for a repeat the current one, null when there is none); after a night shift ends past midnight it is the
ended day while `state.workday` is null. Requests of one person are serialized (row lock on the user), so parallel requests never create a second
workday or a second open interval; exactly one of them answers 201.
Errors: 400 `VALIDATION_FAILED` (field `type`: missing or unknown); 409 `WORKDAY_NOT_STARTED` (`BREAK` or `END` before the day started: "Start your
workday before taking a break." / "You have not started a workday."; also `BREAK` after the day ended); 409 `NOT_ON_BREAK` (`RESUME` without a break:
"You are not on a break."); 409 `BREAK_IN_PROGRESS` (`END` while on a break: "You are on a break. Select Back to Work before ending your workday.");
403 `FORBIDDEN` ("This account is deactivated.", when the account was deactivated while the request was waiting); 500 `TIME_RECORD_INCONSISTENT`
(the open workday's intervals do not match its status; an administrator has to correct it).
Audit (applied events only, actor = subject = the person, entity `workday`): `time.workday_started`, `time.workday_reopened`, `time.break_started`,
`time.work_resumed`, `time.workday_ended`, each with before `{ status }` and after `{ status, at }`.

### GET /api/workdays/mine
The signed-in person's workdays dated in a range. Query: `range` = `today` | `yesterday` | `week` (Monday to today) | `month` (default; the 1st to today) |
`custom` with `from` / `to` (`YYYY-MM-DD`; a missing `from` is this week's Monday, a missing `to` today, reversed ends are swapped, at most 366 days: an
earlier `from` is moved up); `sort` = `date` (default) | `startedAt` | `endedAt` (open days last when ascending) | `productiveSeconds` | `breakSeconds` |
`spanSeconds` | `breakCount`; `sortDirection` (**default `desc` here**: newest first), `page`, `pageSize` (list parameters; `search` is ignored).
"Today" is the date in the business time zone. 400 for an unknown `range`, a malformed `from` / `to` or a bad list parameter.
`data = { rows, total, page, pageSize, range, from, to, totals, dailySeries }`:
- row `{ id, date, status, isStale, startedAt, endedAt | null, productiveSeconds, breakSeconds, spanSeconds, breakCount }` (an open day's figures run to
  `now`; an ended day's come from the totals stored when it ended);
- `totals = { workdays, productiveSeconds, breakSeconds, spanSeconds, breakCount }` over the whole range (every page);
- `dailySeries = [{ date, productiveSeconds, breakSeconds, workdays }]`, one point for every date from `from` to `to` (zero when nothing was recorded).

### GET /api/workdays/{workdayId}
`data = <workday>` with every work and break interval. Only the person's own workdays: anybody else's (also their manager or the owner, for now) and
another business's answer 404 `NOT_FOUND` ("Workday not found."); 400 for a malformed id.

## Not an endpoint yet

`ReportingScopeService.visibleEmployeeIds(businessId, managerId, depth)`: the users a manager may see, depth 1 (direct reports) or 2 (also their reports);
never the manager, nobody above or beside them; deactivated reports included. The Team tab endpoints (module 3) will use it.
