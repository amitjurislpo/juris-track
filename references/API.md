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
  cover), 413 `PAYLOAD_TOO_LARGE` (a request body above 64 KB, refused before it is read), 422 business rule (specific codes below), 429 `RATE_LIMITED`,
  500 `INTERNAL_ERROR`.
- **Time codes**: 409 `WORKDAY_NOT_STARTED`, `NOT_ON_BREAK`, `BREAK_IN_PROGRESS` (time events); 403 `CANNOT_CORRECT_OWN_TIME`; 422
  `TIME_INTERVAL_OVERLAP`, `INTERVAL_STILL_RUNNING`, `WORKDAY_MUST_START_WITH_WORK`, `CORRECTION_OUT_OF_RANGE`, `WORKDAY_OVERLAP` (corrections); 422
  `EXPORT_TOO_LARGE` (exports); 500
  `TIME_RECORD_INCONSISTENT`.
- **Types**: ids are UUID strings; timestamps are ISO-8601 UTC strings (`2026-10-22T09:30:00.123456Z`); time zones are IANA ids; roles are `SUPER_ADMIN`,
  `ADMIN` (the business owner), `MANAGER`, `EMPLOYEE`.
- **Lists** (`GET` on a collection): query parameters `search` (text, cut to 100 characters), `sort` (one of the endpoint's keys), `sortDirection`
  (`asc` default | `desc`), `page` (from 1, default 1), `pageSize` (1 to 100, default 25) plus the endpoint's filters. An unknown value is 400 with the
  parameter as `field`. Answer: `data = { rows: [...], total, page, pageSize }` (`total` = rows on all pages).
- **PATCH**: a field that is left out stays as it is; a field sent as `null` is cleared where that is allowed (else 400). `PUT .../status` is idempotent:
  setting the state a record already has changes nothing and writes no audit entry.
- **Request id**: every answer carries `X-Request-Id`. A caller may send its own (1 to 64 letters, digits, `.`, `_`, `:`, `-`; the JurisOne gateway
  passes its own); anything else is replaced by a new UUID. The id is stored in every audit entry of the request.
- **Audit**: every write below is written to the audit log (actor, action, subject, record before/after, reason, request) in the same transaction;
  refusals, security events and reads of other people's data are audited too. Catalogue, volume rules and the read API: section "Audit log".

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
| PUT | `/api/businesses/{businessId}/jurisone-link` | SUPER_ADMIN | Link a standalone business to its JurisOne business |
| DELETE | `/api/businesses/{businessId}/jurisone-link` | SUPER_ADMIN | Remove that link |
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
| GET | `/api/workdays/{workdayId}` | ADMIN, MANAGER, EMPLOYEE | One workday with its work and break intervals: my own, or one of somebody in my scope |
| GET | `/api/team/members` | ADMIN, MANAGER | Live board of the people in the caller's scope |
| GET | `/api/team/members/{employeeId}` | ADMIN, MANAGER | One person of the scope with their current time state |
| GET | `/api/team/members/{employeeId}/workdays` | ADMIN, MANAGER | One person's history (as `GET /api/workdays/mine`) |
| PATCH | `/api/workdays/{workdayId}/work-sessions/{sessionId}` | ADMIN, MANAGER | Correct a work interval of somebody in scope |
| PATCH | `/api/workdays/{workdayId}/break-sessions/{sessionId}` | ADMIN, MANAGER | Correct a break interval of somebody in scope |
| PUT | `/api/workdays/{workdayId}/status` | ADMIN, MANAGER | End an open (forgotten) workday of somebody in scope |
| GET | `/api/reports/workdays` | ADMIN, MANAGER | Workdays of the people in scope for a date range (rows, totals, daily series); with `Accept: text/csv` the CSV file |
| GET | `/api/reports/summaries` | ADMIN, MANAGER | The same workdays added up per employee, per manager of the day, or per date |
| GET | `/api/audit-logs` | ADMIN, SUPER_ADMIN | The audit log (filters, paging); `format=csv` exports it |
| GET | `/api/audit-logs/{auditLogId}` | ADMIN, SUPER_ADMIN | One audit entry in full |

## Sign-in (standalone)

### POST /api/auth/otp-requests
Body `{ email: string }`. Always 200 `{ ok, message: "If that account exists and is active, a 6-digit code is on its way to the email." }`; no code is sent
to an unknown or deactivated account or to a user of a suspended business. 400 missing/invalid email; 429 too many requests (per IP and per email).
The IP address is the connection's, or, behind a trusted proxy (`juristrack.security.trusted-proxies`, default loopback only), the client it forwarded in
`X-Forwarded-For` (the right-most hop that is not a proxy): a caller cannot choose its own address by writing the header.
Audit: `auth.sign_in_code_requested`, SUCCESS when a code was sent, DENIED with the reason (`unknown_email`, `inactive`, `business_suspended`) otherwise
(the answer is the same; a DENIED entry is written once per address, IP and window); a 429 writes `auth.rate_limited` once per limit, key and window.

### POST /api/auth/sessions
Body `{ email: string, otp: string (6 digits), remember?: boolean }` (`remember` = keep me signed in). 201 with the `jt_at` / `jt_rt` cookies and
`data = { id, firstName, lastName, email, role, businessName }` (`businessName` null for the SUPER_ADMIN). 400 malformed code; 401 wrong, expired or used
code, unknown or deactivated account, or suspended business (always the same message); 429 after too many failures from one IP address (20 per window) or
for one account (10 per window, from whatever address); only failed attempts count.
Audit: `auth.signed_in`; a refusal writes `auth.sign_in_failed` with the reason (`wrong_code`, `expired_or_used_code`, `code_burnt`, `unknown_email`,
`inactive`, `business_suspended`), the wrong try that uses up a code's 5 tries also `auth.sign_in_code_burnt`; a 429 writes `auth.rate_limited` (once per window).

### POST /api/auth/session-refreshes
No body; uses the `jt_rt` cookie. 201 with new cookies. A token used again within `refresh-reuse-grace-seconds` (two requests of one browser at once) gets
a new `jt_at` only, no second `jt_rt` (so a copied token cannot start a parallel chain). 401 (cookies cleared) when the session ended, expired, the user was deactivated or the refresh
token was used twice. Audit: a successful refresh is not audited (every few minutes); a reused token writes `auth.session_revoked_refresh_reuse`, any
other refusal `auth.session_refresh_failed` (reason `unknown`, `revoked`, `expired`, `inactive`; once per reason, IP address, user and window); a
request without the cookie writes nothing.

### DELETE /api/auth/sessions/current
No body. Always 200; ends this device's session and clears the cookies. Audit: `auth.signed_out` when a session was ended (also with only the refresh
cookie; `after.withRefreshCookieOnly`).

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
Audit: `access.business_list_viewed` (platform log; the query parameters, `rowCount`, `total` in `after`), one per request.

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
Audit: `access.business_viewed` in that business's log (subject = its owner), so the owner sees that the platform looked.

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
**Suspending closes every open workday of the business** at that moment (nobody can end or correct a day while suspended, so the suspension must not
count as work time): the open work or break interval ends, the day is `DAY_ENDED` with recomputed totals, one `workday.closed` entry per person (reason
`business_suspended`, actor = the super admin). Reactivating reopens nothing.
Audit: `business.suspended` / `business.reactivated` with the reason.

### PUT /api/businesses/{businessId}/jurisone-link
Body `{ jurisoneBusinessId: uuid (required) }`. Links a **standalone** business to its business in JurisOne: from then on the JurisOne tokens of that
business resolve to it and its people are linked by email on their first token (a token never takes the ADMIN role away from an owner). This is the only
way an existing business is linked: a JurisOne token never adopts a business by email (that would let any owner plant an email and take over a company).
200 `{ ok, message: "Business linked to JurisOne.", data: <as GET /api/businesses/{businessId}> }` (`isLinkedToJurisone: true`); the same link again changes
nothing and writes no entry. Errors: 400 (`jurisoneBusinessId` missing or not a UUID), 404, 409 `BUSINESS_ALREADY_LINKED` (linked to another JurisOne
business: remove the link first), 409 `JURISONE_BUSINESS_IN_USE` (another business has that JurisOne id; a JurisOne business that already got a
business of its own by a token is in this state). Audit: `integration.business_linked` (category `integration`, in the business's log, `after.jurisoneBusinessId`).

### DELETE /api/businesses/{businessId}/jurisone-link
No body. Removes the link; the business stays a standalone one and its people keep their accounts (their JurisOne employee ids stay recorded). 200
`{ ok, message: "Business unlinked from JurisOne.", data: <detail> }` (`isLinkedToJurisone: false`); a business that is not linked is left as it is
(nothing written). 404. Audit: `integration.business_unlinked` (`before.jurisoneBusinessId`).

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
Audit: `access.employee_list_viewed`, one per request, with the query parameters, `rowCount` and `total` in `after`.

### POST /api/employees
Body: `{ firstName: 1-60, lastName: 1-60, email: valid, at most 200, employeeCode?: letters/digits/-/_, at most 32, role?: "EMPLOYEE" (default) | "MANAGER",
managerId?: uuid }`. The employee is active at once and signs in with an emailed code. 201 `{ ok, message: "Employee added.", data: { id } }`.
Errors: 400 (fields `firstName`, `lastName`, `email`, `employeeCode`; an unknown role or malformed `managerId` is a 400 without field), 409 `EMAIL_IN_USE`
(any business), 409 `EMPLOYEE_CODE_IN_USE` (this business), 422 `ROLE_NOT_ASSIGNABLE` (role ADMIN or SUPER_ADMIN), 422 manager rules (below).
Audit: `employee.created`; a 422 writes `employee.change_refused` (outcome DENIED, the code as reason) although nothing is created.

### GET /api/employees/{employeeId}
`data = <employee>`. 404. Audit: `access.employee_viewed` (subject = the employee), not when the owner opens their own record.

### PATCH /api/employees/{employeeId}
Body (each optional): `{ employeeCode: string | null, firstName: string, lastName: string, email: string, role: "EMPLOYEE" | "MANAGER", managerId: uuid | null }`.
`null` clears `employeeCode` or `managerId`; `null` for a name, the email or the role is a 400. 200 `{ ok, message: "Employee saved.", data: <employee> }`.
Errors: 400, 404, 409 `EMAIL_IN_USE`, 409 `EMPLOYEE_CODE_IN_USE`, 422 `ROLE_NOT_ASSIGNABLE` (giving ADMIN / SUPER_ADMIN, or changing an ADMIN's role),
422 `MANAGER_HAS_REPORTS` (a MANAGER with active reports cannot become an EMPLOYEE: give the reports another manager first), 422 manager rules.
Audit: `employee.updated` with the whole record before and after (`employeeCode`, `firstName`, `lastName`, `email`, `role`, `isActive`, `managerId`;
only when something changed); a 422 writes `employee.change_refused` (DENIED, the code as reason). A role change applies on the user's next request.

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
`{ status: "COMPLETED", endedAt }`, the reason given, else "Employee deactivated."); a 422 writes `employee.change_refused` (DENIED, the code as reason).

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
(or now). `breakCount` counts the running break too. `isStale` ("not ended") = the day is still open and its last uninterrupted stretch (from the start, or from
the last time it was started again after the day had ended) is more than the business's
`staleWorkdayHours` (computed when read), so a day reopened late in the evening is not "not ended" because of the morning. `segments` are oldest first.
**Precision**: events are stamped to the millisecond (what a browser keeps), never before the latest instant already recorded for the person (a clock that
steps back, or an instance whose clock runs behind, cannot put an interval before the previous one: the event is stamped at that latest instant).

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
workday or a second open interval; exactly one of them answers 201. A collision on one of those keys that no concurrent request explains (every writer holds the
person's lock) means inconsistent records and is a 500 `TIME_RECORD_INCONSISTENT`, never an `alreadyApplied` repeat.
Errors: 400 `VALIDATION_FAILED` (field `type`: missing or unknown); 409 `WORKDAY_NOT_STARTED` (`BREAK` or `END` before the day started: "Start your
workday before taking a break." / "You have not started a workday."; also `BREAK` after the day ended); 409 `NOT_ON_BREAK` (`RESUME` without a break:
"You are not on a break."); 409 `BREAK_IN_PROGRESS` (`END` while on a break: "You are on a break. Select Back to Work before ending your workday.");
403 `FORBIDDEN` ("This account is deactivated.", when the account was deactivated while the request was waiting); 500 `TIME_RECORD_INCONSISTENT`
(the open workday's intervals do not match its status; an administrator has to correct it).
Audit (applied events only, actor = subject = the person, entity `workday`): `time.workday_started`, `time.workday_reopened`, `time.break_started`,
`time.work_resumed`, `time.workday_ended`, each with before `{ status }` and after `{ status, at }`. A repeat writes nothing; a 500
`TIME_RECORD_INCONSISTENT` writes `time.event_failed` (outcome FAILED, `after.type`). `GET /api/me/time` (polled) is never audited.

### GET /api/workdays/mine
The signed-in person's workdays dated in a range. Query: `range` = `today` | `yesterday` | `week` (Monday to today) | `month` (default; the 1st to today) |
`custom` with `from` / `to` (`YYYY-MM-DD`; a missing `from` is this week's Monday, a missing `to` today, reversed ends are swapped, at most 366 days: an
earlier `from` is moved up); `sort` = `date` (default) | `startedAt` | `endedAt` (open days last when ascending) | `productiveSeconds` | `breakSeconds` |
`spanSeconds` | `breakCount`; `sortDirection` (**default `desc` here**: newest first), `page`, `pageSize` (list parameters; `search` is ignored).
"Today" is the date in the business time zone. 400 for an unknown `range`, a malformed `from` / `to` or a bad list parameter.
`data = { rows, total, page, pageSize, range, from, to, totals, dailySeries }`:
- row `{ id, date, status, isStale, startedAt, endedAt | null, productiveSeconds, breakSeconds, spanSeconds, breakCount }` (an open day's figures run to
  `now`; an ended day's come from the totals stored when it ended);
- `totals = { workdays, productiveSeconds, breakSeconds, spanSeconds, breakCount, averageProductiveSeconds }` over the whole range (every page);
  `averageProductiveSeconds` = productive seconds per workday of the range, rounded half up, 0 without workdays (see "Averages");
- `dailySeries = [{ date, productiveSeconds, breakSeconds, workdays }]`, one point for every date from `from` to `to` (zero when nothing was recorded).

### GET /api/workdays/{workdayId}
`data = <workday>` with every work and break interval. One's own workdays, and (MANAGER, ADMIN) a workday of anybody in the caller's scope (section
"Who sees and corrects whom"). Anybody else's, another business's and an unknown id answer 404 `NOT_FOUND` ("Workday not found."); 400 for a malformed
id. Reading somebody else's workday is audited (`access.workday_viewed`, subject = the person, entity `workday`, after `{ date }`); reading one's own
is not.

## Team tab (managers and the owner)

EMPLOYEE and SUPER_ADMIN get 403 `FORBIDDEN` on every Team and correction endpoint; signed out 401.

### Who sees and corrects whom (scope)

The reporting line (`users.manager_id`) decides, read when the request runs (not the manager stored on old workdays), through
`ReportingScopeService` (depth 1 = direct reports, depth 2 = also their reports; never the caller, nobody above or beside them; cycle-safe):

| Caller | Board (`GET /api/team/members`) and reports (`scope`) | One person, their history, their workdays, corrections |
|---|---|---|
| MANAGER | `scope=direct` (default): their direct reports; `scope=indirect`: also the reports of those (one level further down) | direct and indirect reports (depth 2), whatever the board's switch shows |
| ADMIN (owner) | `scope=all` (default): everybody of the business; `scope=direct` / `indirect`: their own reporting line as for a manager | everybody of the business |

- The caller is never in their own list or scope: their own record is the personal section (`/api/me/time`, `/api/workdays/mine`), and
  `GET /api/team/members/{ownId}` answers 404.
- People above or beside a manager, people of another business, unknown ids and out-of-scope ids all answer **404** `NOT_FOUND` (never 403), so a
  caller cannot learn that a record exists.
- **Nobody corrects their own time**: 403 `CANNOT_CORRECT_OWN_TIME` ("You cannot correct your own time. Ask your manager or the owner to do it."), also
  for the owner. A manager's time is corrected by their own manager or the owner.
- Deactivated people stay in the scope for reading and correcting their past days (their history stays visible); the board leaves them out (below).

### GET /api/team/members
Query:
- `scope` = `direct` | `indirect` (MANAGER, default `direct`) or `all` | `direct` | `indirect` (ADMIN, default `all`); any case. Anything else (also
  `all` for a manager) is 400 with field `scope`.
- `status` = `WORKING` | `ON_BREAK` | `NOT_STARTED` | `DAY_ENDED` | `all` (default; any case); else 400 (field `status`).
- `search`: first name, last name, "first last", email or employee code contains the text (case-insensitive).
- `sort` = `name` (default; first then last name) | `status` (ascending: WORKING, ON_BREAK, NOT_STARTED, DAY_ENDED) | `productiveSeconds` |
  `lastActivity` (people without activity first when ascending); `sortDirection` (`asc` default | `desc`); ties are ordered by name.
- `page`, `pageSize` (list parameters).

Who is on the board: the people of the scope who are **active**, plus a deactivated person whose workday is still open (should not happen:
deactivation closes it). Each person's state is **today's** in the business time zone, exactly as `GET /api/me/time` decides it for oneself: the open
workday whatever its date (a night shift started yesterday is `WORKING` with yesterday's `date`), else today's ended workday (`DAY_ENDED`), else
`NOT_STARTED`. A night shift that ended after midnight is not today's: the person is `NOT_STARTED` again.

`data = { rows, total, page, pageSize, scope, serverTime, today, businessTimeZone, staleWorkdayHours, metrics }`:
- `total` = rows matching `status` and `search` (all pages); `scope` = the scope used (lower case); `today` = `YYYY-MM-DD` in the business time zone;
  `serverTime` lets the client measure its clock offset.
- row: `{ employee: { id, employeeCode, firstName, lastName, email, role, isActive, managerId, managerName }, status, workdayId | null, date | null,
  startedAt | null, endedAt | null, isStale, closedProductiveSeconds, closedBreakSeconds, activeWorkStartedAt | null, activeBreakStartedAt | null,
  productiveSeconds, breakSeconds, spanSeconds, breakCount, lastActivity: { type, at } | null }`. The time fields mean exactly what they mean in the
  workday snapshot (live values up to `serverTime`), so the client runs the same live timer. `NOT_STARTED` rows have every number 0 and every time /
  id null. `managerName` = "First Last" of the person's manager, null without one.
- `metrics = { total, working, onBreak, notStarted, dayEnded, stale, totalProductiveSeconds, totalBreakSeconds, averageProductiveSeconds }` over
  **everybody on the board in the chosen scope**: `status`, `search` and paging do not change them. `averageProductiveSeconds`: see "Averages" below.

The board is read in batches (people, workdays, work intervals, break intervals: one query each), whatever its size. Reading the board is **not**
audited (it refreshes every few seconds and shows only today's state); opening one person is.

### GET /api/team/members/{employeeId}
`data = { employee: <as in the board row>, serverTime, today, businessTimeZone, staleWorkdayHours, status, workday | null }`, where `status` and
`workday` (the full workday snapshot with `segments`) are chosen as `GET /api/me/time` chooses them for oneself. A deactivated person can be opened.
404 `NOT_FOUND` ("Employee not found.") outside the scope; 400 for a malformed id.
Audit (access): `access.team_member_viewed` (actor = caller, subject = the person, entity `user`), one per request.

### GET /api/team/members/{employeeId}/workdays
The person's workdays with the query parameters, sorting, defaults (`sortDirection` default `desc`) and answer of `GET /api/workdays/mine`:
`data = { rows, total, page, pageSize, range, from, to, totals, dailySeries }`. 404 outside the scope; 400 for a bad parameter (e.g. field `range`).
Audit (access): `access.team_member_workdays_viewed` (subject = the person, entity `user`, after `{ range, from, to }`), one per request.

## Time corrections (managers and the owner)

ADMIN or MANAGER, within the scope above, never on one's own records. The changed workday is answered as `data = <workday snapshot>` (with every
interval). Rules:
- **Reason**: `reason` is mandatory, 5 to 500 characters after trimming (400 field `reason`: "Give a reason of at least 5 characters.").
- **Times** are ISO-8601 instants with `Z` or an offset (`2026-10-22T09:30:00Z`, `2026-10-22T15:00:00+05:30`), stored in UTC; a time without an
  offset or anything else is 400 ("Send the time as an ISO-8601 instant, for example 2026-10-22T09:30:00Z.").
- Times have the **millisecond** precision a browser keeps: a time sent back exactly as it was read (older records may carry microseconds) is no change
  and does not write a "Time corrected" entry; the last-activity end offered by a client is accepted.
- No time may lie in the future (400 "Times cannot be in the future."); an interval must end **after** it starts (400 field `endedAt`: "The end time
  must be after the start time.").
- The intervals of a workday never overlap, work with work, break with break **and** work with break (422 `TIME_INTERVAL_OVERLAP`: "The corrected
  interval overlaps another work or break interval."); a running interval lasts until now, so only the latest interval may run. Intervals that only
  touch (one ends when the next starts) do not overlap.
- The first interval of a workday is a work interval (422 `WORKDAY_MUST_START_WITH_WORK`), so every break lies inside the day.
- **Window**: a corrected time (and the end of a closed workday) must lie from the start of the workday's date to the end of the **next** date, in the zone
  the workday was started in (a night shift may run into the next morning), and the first interval must start on the date itself; else 422
  `CORRECTION_OUT_OF_RANGE`. A year typed wrong, or year 1, can no longer add years of work or move hours into another day.
- **Other workdays**: the corrected workday (its first start to its last end, or to now while it runs) must not overlap another workday of the same person
  (422 `WORKDAY_OVERLAP`: the same hours would count twice in the reports); touching is fine.
- A running interval keeps running: only its start moves (`endedAt` left out or `null`); a value for `endedAt` is 422 `INTERVAL_STILL_RUNNING`
  ("This interval is still running. Close the workday instead of setting an end time."). A closed interval stays closed (`endedAt: null` is 400
  "An end time is required.").
- The workday's `startedAt` becomes the start of its first interval and, when it has ended, its `endedAt` the end of its last interval. Its `date`
  never changes. A `DAY_ENDED` workday stays ended. Its stored totals are recomputed from the intervals (so they always equal the sum of the
  intervals); an open workday keeps none (it is computed live).
- Each correction locks the employee's row first, like `POST /api/me/time-events`, so it never interleaves with the employee's own start, break,
  resume or end; the state is read after the lock.

Error order: 401 / 403 role, then 400 for the body, then 404 (unknown workday, another business, out of scope) or 403 `CANNOT_CORRECT_OWN_TIME`,
then 404 for the interval, then the rules above (400 / 422). A 422 is audited as `time.correction_refused` (outcome DENIED, the code as reason,
`after.operation` = `adjust_work_session` | `adjust_break_session` | `close_workday`), written after the rollback.

### PATCH /api/workdays/{workdayId}/work-sessions/{sessionId}
### PATCH /api/workdays/{workdayId}/break-sessions/{sessionId}
Body: `{ startedAt?: string, endedAt?: string | null, reason: string }` (PATCH: a field left out stays as it is; `startedAt: null` is 400).
200 `{ ok, message: "Time corrected.", data: <workday> }`; when the times equal the stored ones nothing is written and the answer is 200 with
`message: "Nothing changed."`. 404 "Time record not found." when the interval does not exist, belongs to another workday or is of the other type
(a break id on `work-sessions`). Other errors as above.
Audit: `time.session_adjusted`, actor = caller, subject = the employee, entity `work_session` / `break_session` with the interval's id, the reason,
before / after `{ interval: { id, type: "WORK" | "BREAK", startedAt, endedAt }, workday: { id, date, status, startedAt, endedAt, productiveSeconds,
breakSeconds, spanSeconds, breakCount } }` (an open day's totals as of the correction).

### PUT /api/workdays/{workdayId}/status
Body: `{ status: "DAY_ENDED" (any case; nothing else can be set, 400 field status), endedAt?: string, reason: string }`. Ends an open workday,
typically a forgotten (stale) one: the running work or break interval ends at `endedAt` and the day becomes `DAY_ENDED` with recomputed totals.
Without `endedAt` the end is **now** while an interval is running (the old app's rule), otherwise the end of the last interval; either way it must lie in the
workday's window (see Window above): a day forgotten for days is closed with an `endedAt` on its own date or the next (else 422 `CORRECTION_OUT_OF_RANGE`).
`endedAt` may not be in
the future nor before the last recorded activity (the start of the running interval or the end of any interval): 400 field `endedAt` ("The end time
cannot be before the last recorded activity."). Any open workday can be ended, stale or not.
200 `{ ok, message: "Workday ended.", data: <workday> }`. Idempotent: a workday that has already ended is left as it is, answered 200 with
`message: "This workday had already ended."` and no audit entry. Afterwards the employee may start again; a day ended on today's date is reopened by
their next `START` as usual.
Audit: `time.workday_closed`, entity `workday`, the reason, before `{ workday: <summary as above> }`, after `{ workday: <summary>, closedIntervals:
[{ id, type, startedAt, endedAt }] }`. (Closing because of a deactivation keeps its own action `workday.closed`.)

## Reports (managers and the owner)

EMPLOYEE and SUPER_ADMIN get 403 `FORBIDDEN`; signed out 401. Scope as in "Who sees and corrects whom" (board column); `scope=all` for a MANAGER is
403 `FORBIDDEN` ("You can see only the people who report to you."). The owner's `all` includes the owner. Deactivated people stay in scope.

**Shared query parameters** (both endpoints):

| Parameter | Values | Default |
|---|---|---|
| `range` | `today`, `yesterday`, `week` (Monday to today), `month` (the 1st to today), `custom` | `week` |
| `from`, `to` | `YYYY-MM-DD`, with `range=custom`: a missing `from` is this week's Monday, a missing `to` today, reversed ends are swapped, at most 366 days (an earlier `from` is moved up) | |
| `scope` | `direct`, `indirect`, `all` (any case) | `all` for the owner, `direct` for a manager |
| `employeeId` | one person (uuid); must be in scope, else 404 `NOT_FOUND` "Employee not found." (also unknown ids and other businesses' people) | |
| `managerId` | only workdays whose **manager of the day** (the workday's `manager_id` snapshot) is this user. A manager may give themselves or someone in scope; the owner with `scope=all` anyone of the business; else 404 "Manager not found." | |
| `status` | one or more of `WORKING`, `ON_BREAK`, `DAY_ENDED`, `NOT_ENDED`, comma-separated, any case. `NOT_ENDED` = an open day that is stale (such a day also matches `WORKING` / `ON_BREAK`) | every status |
| `search` | text matched against the employee's first name, last name, "first last", email and employee code (`%` `_` literal) | |
| `sort`, `sortDirection`, `page`, `pageSize` | list parameters; the sort keys are per endpoint (below) | |

A workday belongs to the date it **started** on in the business time zone. 400 `VALIDATION_FAILED` with `errors[].field` for every bad parameter at
once (`range`, `from`, `to`, `scope`, `employeeId`, `managerId`, `status`, `groupBy`, `sort`, `sortDirection`, `page`, `pageSize`, and `timeZone` for
the CSV). Order of checks: 400, then 403 (`scope=all` for a manager), then 404 (`employeeId`, `managerId`); the CSV's `timeZone` (400) and row cap
(422) after these.

**Figures**: an ended workday shows the totals stored when it ended (`spanSeconds` = end - start); an open one is computed from its intervals up to the
request time. Every figure covers the whole range (all pages). `totals = { workdays, employees, productiveSeconds, breakSeconds, spanSeconds,
breakCount, averageProductiveSeconds }` (`employees` = different people). `dailySeries = [{ date, productiveSeconds, breakSeconds, workdays }]`, one
point for every date from `from` to `to`; the series and the totals always add up to the rows. The range is read set-based (one query for the scope,
one for the workdays with employee and manager joined, one each for the intervals of the open days; index `workdays (business_id, work_date)`, V11).

### GET /api/reports/workdays
Sort keys: `date` (default), `employee` (name, case-insensitive), `startedAt`, `productiveSeconds`, `breakSeconds`, `spanSeconds`, `breakCount`.
**Default `sortDirection` is `desc`** (newest first). Ties: date (newest first), employee name, start, id.
`data = { rows, total, page, pageSize, range, from, to, scope, totals, dailySeries }`, row: `{ id, date, employee: { id, employeeCode | null, name },
manager: { id, name } | null, status: "WORKING" | "ON_BREAK" | "DAY_ENDED", isStale, startedAt, endedAt | null, productiveSeconds, breakSeconds,
spanSeconds, breakCount }`. `manager` is the employee's manager **when the workday started** (snapshot), with that person's current name.
Audit (access): `reports.viewed`, entity `report` / `workdays`, subject = the `employeeId` filter (else null), after `{ report, filters, rowCount }`
(`filters = { range, from, to, scope, employeeId, managerId, status: [...], search, sort, sortDirection }`, resolved values); one per request, never one
per row.

**CSV**: the same resource with `Accept: text/csv` (any quality; no header, `*/*` or `application/json` give the JSON). Same parameters, scope and
checks; **every row of the range** in the requested order (paging ignored). One more parameter, `timeZone` = the business time zone (default) or one of
its `displayTimeZones` (IANA id, any case; anything else 400 field `timeZone`). Answer 200, `text/csv;charset=UTF-8`,
`Content-Disposition: attachment; filename="juristrack-<from>-to-<to>.csv"`, `Cache-Control: no-store`; errors stay `application/problem+json`.
File (as the old app's export): UTF-8 with a byte order mark, CRLF, header `Date,Employee,Employee ID,Manager,Status,Start (<abbr>),End (<abbr>),
Productive hours,Break hours,Span hours,Breaks` (`<abbr>` = the display zone's abbreviation at the moment of the export, from `engine/ZoneNames`), rows
with the date, "First Last", employee code, manager of the day, status (`Working`, `On Break`, `Day Ended`, `Not ended` for a stale open day), start and
end `HH:mm` in the display zone, hours with two decimals (`toFixed(2)`), breaks. A cell starting with `=`, `+`, `-`, `@`, tab or CR gets a leading `'`;
a cell with `"`, `,`, LF or CR is quoted. More than `juristrack.reports.maximum-export-rows` rows (default 50000) is 422 `EXPORT_TOO_LARGE` ("This
export has N rows; at most M fit in one file. Choose a shorter date range or fewer people."), nothing exported or audited.
Audit (access): `reports.exported`, after `{ report, filters, rowCount, timeZone, fileName }`.

### GET /api/reports/summaries
`groupBy` = `employee` (default) | `manager` (the manager of the day; workdays without one form the group with key `none`) | `date`. Sort keys:
`label` (default; case-insensitive; the `none` group last in both directions), `workdays`, `employees`, `productiveSeconds`, `breakSeconds`,
`spanSeconds`, `breakCount`, `averageProductiveSeconds`. Default `sortDirection`: `asc`, but `desc` for `groupBy=date`. Ties: label, key.
`data = { rows, total, page, pageSize, groupBy, range, from, to, scope, totals, dailySeries }`, row: `{ key, label | null, employeeCode | null,
workdays, employees, productiveSeconds, breakSeconds, spanSeconds, breakCount, averageProductiveSeconds, firstStartedAt, lastEndedAt | null }`
(`key` = employee id / manager id / `none` / date; `label` = name / date, null for `none`; `employeeCode` only for `groupBy=employee`).
Audit (access): `reports.viewed`, entity `report` / `summaries`, `filters.groupBy` set, `rowCount` = groups.

A report filtered on the caller themselves (`employeeId` = own id, owner only) is reading one's own data and is not audited.

### Averages
- Board `metrics.averageProductiveSeconds` = `totalProductiveSeconds` divided by the people who started today (working, on a break or day ended),
  rounded down; 0 when nobody started.
- Report `totals.averageProductiveSeconds`, a summary row's `averageProductiveSeconds` and the history's `totals.averageProductiveSeconds` (`GET /api/workdays/mine`,
  `GET /api/team/members/{employeeId}/workdays`) = productive seconds per workday of the range, rounded half up; 0 without
  workdays.

## Audit log

A complete, append-only record of every movement (owner decision, `MIGRATION_PLAN.md` "Audit policy"). Table `audit_logs` (V1, extended by V3).

**An entry**: `createdAt` (UTC, the server's real time), `businessId` (null = a platform event: the super admin's lists and exports, sign-in attempts
for unknown emails, refused tokens), `actor` (who did it; `actorRole` = their role at that moment), `subject` (whom it is about), `action`, `category`
(`auth`, `people`, `business`, `time`, `security`, `access`, `integration`, `audit`), `outcome` (`SUCCESS`; `DENIED` = refused for a rule, a role or a
security reason; `FAILED` = allowed but went wrong), `entityType` / `entityId`, `before` / `after` (JSON), `reason`, and the request: `source`
(`STANDALONE` cookie session or standalone sign-in, `INTEGRATION` JurisOne's Bearer token, `SYSTEM` no HTTP request), `ipAddress` (the connection's address, or,
behind a trusted proxy, the client it forwarded in `X-Forwarded-For`; the same as the rate limits, see `POST /api/auth/otp-requests`), `userAgent` (at most 400 characters), `requestId`,
`httpMethod`, `requestPath` (never the query string).

**Never stored**: sign-in codes, tokens, cookies, secrets. Values of JSON keys that name a password, token, secret, cookie, authorization or one-time code
are replaced by `"[redacted]"`; an email of an unknown account is stored masked (`a***@example.com`). Emails of known people are stored.

**Append-only**: the application never updates or deletes an entry; the database refuses `UPDATE`, `DELETE` and `TRUNCATE` on `audit_logs` (trigger, for
every role including the schema owner) and the business role has no update or delete rights at all. A user or business that appears in the log cannot be
deleted (foreign keys `RESTRICT`; people are deactivated, businesses suspended). **Visibility** (row-level security): inside a business only its
entries; the super admin (system mode) sees all, platform entries only there.

**Retention**: entries are kept for good; there is no automatic deletion. Pruning old entries (if the owner ever wants it) is a database-owner
operation outside the application (drop the trigger, delete, recreate the trigger) and must itself be documented.

### Catalogue

| Action | Category | Outcome | When |
|---|---|---|---|
| `auth.sign_in_code_requested` | auth | SUCCESS / DENIED | a code was sent (every one); DENIED (reason `unknown_email`, `inactive`, `business_suspended`) when no code was sent: once per address, IP, window |
| `auth.signed_in` | auth | SUCCESS | a session was created (`after.isRemembered`) |
| `auth.sign_in_failed` | auth | DENIED | a refused sign-in; reason `wrong_code`, `expired_or_used_code`, `code_burnt`, `unknown_email`, `inactive`, `business_suspended` |
| `auth.sign_in_code_burnt` | auth | DENIED | the wrong try that used up a code's tries |
| `auth.rate_limited` | auth | DENIED | a sign-in rate limit hit (reason `code_requests_per_ip`, `code_requests_per_email`, `failed_sign_ins_per_ip`, `failed_sign_ins_per_email`); once per limit, key, window |
| `auth.signed_out` | auth | SUCCESS | a session was ended by signing out |
| `auth.session_revoked_refresh_reuse` | auth | DENIED | a refresh token was used twice: the session was ended |
| `auth.session_refresh_failed` | auth | DENIED | a refused refresh (reason `unknown`, `revoked`, `expired`, `inactive`); once per reason, IP, user, window |
| `security.access_denied` | security | DENIED | a 403 for a signed-in user (role or scope; `reason` = the code), every request |
| `security.suspended_business_request` | security | DENIED | a 403 `BUSINESS_SUSPENDED` (cookie or token); once per user and window |
| `security.invalid_session_cookie` | security | DENIED | a forged or malformed access cookie (`invalid_token`) or one of an ended session (`unknown_session`, `session_ended`, `session_expired`, `user_inactive`); once per reason, IP, user, window |
| `security.cross_site_request_blocked` | security | DENIED | a 403 `CROSS_SITE_BLOCKED`; once per IP, path and window |
| `security.out_of_scope_access` | security | DENIED | a signed-in user asked, with a 404, for a person or workday outside their scope, in another business or unknown (team, workday, correction, report and employee endpoints); entity type `user` / `workday`, `after.recordType`, no subject (the probed id is not trusted), reason `NOT_FOUND`; once per user and window |
| `access.employee_list_viewed` | access | SUCCESS | the owner listed the people (`after` = filters, `rowCount`, `total`) |
| `access.employee_viewed` | access | SUCCESS | the owner opened another person's record |
| `access.business_list_viewed` | access | SUCCESS | the super admin listed the businesses (platform log) |
| `access.business_viewed` | access | SUCCESS | the super admin opened a business (that business's log, subject = owner) |
| `audit.exported` | audit | SUCCESS | an audit log CSV export (`after` = the filters and `rowCount`) |
| `business.created` / `business.updated` | business | SUCCESS | a business was created / its settings changed (before and after) |
| `business.suspended` / `business.reactivated` | business | SUCCESS | the super admin suspended / reactivated a business (with the reason) |
| `employee.created` / `employee.updated` | people | SUCCESS | a person was added / changed (whole record before and after, role and manager included) |
| `employee.deactivated` / `employee.reactivated` | people | SUCCESS | with the reason; deactivation lists `endedSessions` and `closedWorkdayId` |
| `employee.change_refused` | people | DENIED | a 422 business rule refused a change (`MANAGER_CYCLE`, `LAST_ACTIVE_OWNER`, `CANNOT_DEACTIVATE_SELF`, `ROLE_NOT_ASSIGNABLE`, `MANAGER_*`, ...) |
| `time.workday_started`, `time.workday_reopened`, `time.break_started`, `time.work_resumed`, `time.workday_ended` | time | SUCCESS | an applied time event |
| `time.event_failed` | time | FAILED | a time event refused with 500 `TIME_RECORD_INCONSISTENT` |
| `workday.closed` | time | SUCCESS | somebody else closed a workday because of a deactivation or a suspension of the business (reason `business_suspended`) |
| `time.session_adjusted` | time | SUCCESS | a manager or the owner corrected a work or break interval (interval and workday totals before / after, the reason) |
| `time.workday_closed` | time | SUCCESS | a manager or the owner ended somebody's open workday (`PUT /api/workdays/{workdayId}/status`), with the closed intervals |
| `time.correction_refused` | time | DENIED | a 422 business rule refused a correction (`TIME_INTERVAL_OVERLAP`, `WORKDAY_OVERLAP`, `CORRECTION_OUT_OF_RANGE`, `WORKDAY_MUST_START_WITH_WORK`, `INTERVAL_STILL_RUNNING`, ...) |
| `access.team_member_viewed` | access | SUCCESS | a manager or the owner opened one person's live state |
| `access.team_member_workdays_viewed` | access | SUCCESS | a manager or the owner opened one person's history (`after = { range, from, to }`) |
| `access.workday_viewed` | access | SUCCESS | a manager or the owner opened somebody else's workday (`after.date`) |
| `reports.viewed` | access | SUCCESS | a report was read (`after = { report, filters, rowCount }`), one per request |
| `reports.exported` | access | SUCCESS | the workday report was downloaded as CSV (`after` adds `timeZone`, `fileName`) |
| `integration.business_provisioned` | integration | SUCCESS | a JurisOne token created a business (a token never links an existing one) |
| `integration.business_linked` / `integration.business_unlinked` | integration | SUCCESS | the super admin linked / unlinked a business to JurisOne (`PUT` / `DELETE /api/businesses/{businessId}/jurisone-link`; `after` / `before.jurisoneBusinessId`) |
| `integration.business_updated` | integration | SUCCESS | the token renamed a linked business |
| `integration.user_provisioned` / `integration.user_linked` / `integration.user_updated` | integration | SUCCESS | a token created / linked / changed a user |
| `integration.token_rejected` | integration | DENIED | a refused JurisOne token: reason code only (`bad_signature`, `expired`, `wrong_issuer`, `wrong_audience`, `malformed_token`, `missing_issue_time`, `lifetime_too_long`, `replayed_token`, `missing_claim: <name>`, ...), never the token; once per reason, IP, window |
| `integration.user_refused` | integration | DENIED | a valid token whose person may not use JurisTrack (`user_deactivated`, `email_of_another_business`, `conflict`); once per reason, user, window |

**Volume decisions**: one entry per write; reads of somebody else's data one entry per request (the Team tab's live board, which refreshes every few
seconds, is not recorded; opening one person, their history or workday, and every report and export is); never audited: reading one's own data
(`GET /api/me`, `/api/me/time` (polled), `/api/workdays/mine`, one's own workday, one's own employee record, `GET /api/business`), a successful session
refresh, a 401 without a session or with an access token that merely ran out, validation errors and conflicts (400, 409), a 404 for a route or a record
of one's own scope that does not exist (but a 404 for somebody else's person or workday is `security.out_of_scope_access`, once per window), viewing the audit log
(only exports). High-volume security events (rate limits, refused refreshes, bad cookies, refused tokens, suspended-business requests, cross-site
writes) are written at most once per key in each 15-minute window (in memory per API instance; `after.aggregationWindowMinutes`). Refusals and failures
are written in a transaction of their own, so they stay although the request fails. Reads of other people's data write their entry **in the read's own
transaction** (one database connection per request; the data is never served without its entry). Entries that nobody signed in caused (sign-in attempts,
refused tokens and cookies) are capped per IP address and window (`juristrack.audit.unauthenticated-entries-per-ip`, default 500): the log is append-only.

### GET /api/audit-logs
ADMIN (their business) and SUPER_ADMIN (every entry); MANAGER and EMPLOYEE 403. Query: list parameters (`sort` = `createdAt` (default) | `action` |
`category` | `outcome` | `actor` | `subject`; **`sortDirection` default `desc`**: newest first; ties newest first), and the filters `from`, `to`
(`YYYY-MM-DD`, inclusive; in the business time zone for the owner, UTC for the super admin; reversed ends are swapped), `actorId`, `subjectId`, `action`
(exact, or a prefix when it ends with a dot: `time.`), `category`, `entityType`, `entityId`, `outcome` and `source` (any case), `search` (action, actor
and subject name, request id, entity id, IP address); SUPER_ADMIN only: `businessId` = a business id, or `none` for the entries outside any business
(the owner's `businessId` is ignored). 400 for a malformed value (the parameter as `field`).
`data = { rows, total, page, pageSize }`, row: `{ id, createdAt, businessId, businessName, actor: { id, name, role } | null, subject: { id, name } | null,
action, category, outcome, entityType, entityId, source, ipAddress, requestId, reason, summary }`. Names are resolved when read (a renamed person shows
the current name); a person outside the owner's business (the platform super admin) has `name` null and only `role`. `summary` is machine-made: the
changed fields of an update (`"changed: role, managerId"`), else the reason of a refusal or failure, else null. Viewing is not audited.

**CSV** (`format=csv`, any other `format` than `json` / `csv` is 400): the same filter and sort, every row (paging ignored), at most 10,000 rows, else
422 `EXPORT_TOO_LARGE` ("The export would hold N entries; narrow the filters to at most 10000."). `text/csv; charset=UTF-8`, UTF-8 byte order mark, CRLF,
`Content-Disposition: attachment; filename="audit-log-<today>.csv"`. Columns: Time (UTC), Business, Actor, Actor role, Subject, Action, Category, Outcome,
Entity type, Entity id, Source, IP address, Request id, Method, Path, Reason, Summary, Before, After (JSON). Every cell is quoted; a cell starting with
`=`, `+`, `-`, `@`, tab or CR gets a leading `'`. Audit: `audit.exported` (the owner's in their business, the super admin's in the platform log).

### GET /api/audit-logs/{auditLogId}
ADMIN, SUPER_ADMIN. `data` = the row plus `before`, `after` (JSON or null), `userAgent`, `httpMethod`, `requestPath`. 404 unknown or of another business
(the owner), 400 malformed id. Not audited.

## Database migrations

Flyway, `backend/src/main/resources/db/migration`, each with its rollback in `db/rollback`: `V1__baseline` (schema, row-level security),
`V2__business_suspension_and_email_check`, `V3__complete_append_only_audit`, `V11__workdays_business_date_index` (reports). Applied migrations are never
edited; the next one is V12 (Flyway refuses a new version lower than the highest applied one).
