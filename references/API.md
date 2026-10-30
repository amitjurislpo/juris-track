# JurisTrack API

**Document version 2.2** (2026-10-30; 1.1 last break and "Work started", 1.2 login access, 2.0 shift-based calculation: shifts, roster, shift fields on every workday, extra time, 2.1 JurisOne roster sync, 2.2 API versioned under `/api/v1`). The version table is in `FRD-JurisTrack.md`.

The contract between this backend and its clients (the `juris-track-frontend` web app and, embedded, the JurisOne gateway). Keep it exactly in
sync with the code: an endpoint, parameter or field is added, renamed or removed here in the same change as in the controller.

## Conventions (every endpoint)

- **Base path** `/api/v1` (every endpoint; only `/actuator/health` is unversioned). JSON in and out (`Content-Type: application/json`).
- **Authentication**: standalone, the `jt_at` / `jt_rt` cookies set by `POST /api/v1/auth/sessions`; embedded in JurisOne, `Authorization: Bearer <JurisOne token>`
  (see `INTEGRATION.md`). Both produce the same signed-in user and the same rules. The one exception is `PUT /api/v1/integration/employees`, which takes
  JurisOne's service token (no user) and nothing else.
- **Origin check**: a cookie-authenticated `POST`, `PUT`, `PATCH` or `DELETE` must carry `Origin` equal to the web app's URL, else 403 `CROSS_SITE_BLOCKED`.
  Bearer requests are exempt.
- **Success body**: `{ "ok": true, "message"?: string, "data"?: ... }`. Empty `message` / `data` are left out; `null` inside `data` is kept.
- **Errors**: `application/problem+json` (RFC 7807): `{ type, title, status, detail, instance, code, errors? }`. `detail` is the message to show,
  `code` is stable for programs, `errors` = `[{ field, message }]` on 400 validation errors (the first one is `detail`).
- **Common codes**: 400 `VALIDATION_FAILED` (also malformed JSON, a malformed id in the path or a wrong type), 401 `UNAUTHENTICATED` / `TOKEN_EXPIRED`
  (refresh the session and retry), 403 `FORBIDDEN` (signed in, wrong role), 403 `BUSINESS_SUSPENDED` (the user's business is suspended; signing out still
  works), 403 `CROSS_SITE_BLOCKED`, 404 `NOT_FOUND` (also a record of another business), 409 `CONFLICT` (a duplicate the specific codes below do not
  cover), 413 `PAYLOAD_TOO_LARGE` (a request body above 64 KB, refused before it is read; the roster sync takes up to 16 MB), 422 business rule (specific codes below), 429 `RATE_LIMITED`,
  500 `INTERNAL_ERROR`.
- **Time codes**: 409 `WORKDAY_NOT_STARTED`, `NOT_ON_BREAK`, `BREAK_IN_PROGRESS` (time events); 403 `CANNOT_CORRECT_OWN_TIME`; 422
  `TIME_INTERVAL_OVERLAP`, `INTERVAL_STILL_RUNNING`, `WORKDAY_MUST_START_WITH_WORK`, `CORRECTION_OUT_OF_RANGE`, `WORKDAY_OVERLAP` (corrections); 422
  `EXPORT_TOO_LARGE` (exports); 422 `SHIFT_NOT_FOUND`, `SHIFT_INACTIVE`, `SHIFT_IN_USE`, `DEFAULT_SHIFT_REQUIRED`,
  `SHIFT_EFFECTIVE_DATE_IN_PAST`, `SCHEDULE_DATE_TOO_FAR`, `SCHEDULE_DATE_HAS_WORKDAY` and 409 `SHIFT_NAME_IN_USE` (shifts and roster); 500
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
| POST | `/api/v1/auth/otp-requests` | public | Email a sign-in code |
| POST | `/api/v1/auth/sessions` | public | Sign in with the code |
| POST | `/api/v1/auth/session-refreshes` | public (refresh cookie) | Renew the session |
| DELETE | `/api/v1/auth/sessions/current` | public | Sign out |
| GET | `/api/v1/me` | any signed-in user | Who is signed in |
| GET | `/api/v1/businesses` | SUPER_ADMIN | List businesses |
| POST | `/api/v1/businesses` | SUPER_ADMIN | Create a business and its owner |
| GET | `/api/v1/businesses/{businessId}` | SUPER_ADMIN | One business |
| PATCH | `/api/v1/businesses/{businessId}` | SUPER_ADMIN | Change a business's settings |
| PUT | `/api/v1/businesses/{businessId}/status` | SUPER_ADMIN | Suspend / reactivate |
| PUT | `/api/v1/businesses/{businessId}/jurisone-link` | SUPER_ADMIN | Link a standalone business to its JurisOne business |
| DELETE | `/api/v1/businesses/{businessId}/jurisone-link` | SUPER_ADMIN | Remove that link |
| GET | `/api/v1/business` | ADMIN | The owner's business settings |
| PATCH | `/api/v1/business` | ADMIN | Change the owner's business settings |
| GET | `/api/v1/employees` | ADMIN | List the business's people |
| POST | `/api/v1/employees` | ADMIN | Add an employee |
| GET | `/api/v1/employees/{employeeId}` | ADMIN | One employee |
| PATCH | `/api/v1/employees/{employeeId}` | ADMIN | Change an employee |
| PUT | `/api/v1/employees/{employeeId}/status` | ADMIN | Deactivate / reactivate |
| PUT | `/api/v1/employees/{employeeId}/login-access` | ADMIN | Switch the person's sign-in off / on (they stay in the business) |
| GET | `/api/v1/shifts` | ADMIN, MANAGER, EMPLOYEE | The business's shifts (filter `isActive`) |
| POST | `/api/v1/shifts` | ADMIN | Create a shift |
| GET | `/api/v1/shifts/{shiftId}` | ADMIN, MANAGER, EMPLOYEE | One shift |
| PATCH | `/api/v1/shifts/{shiftId}` | ADMIN | Change a shift (future occurrences only) |
| PUT | `/api/v1/shifts/{shiftId}/status` | ADMIN | Deactivate / reactivate a shift |
| PUT | `/api/v1/employees/{employeeId}/shift` | ADMIN | Give an employee a shift from a date on |
| GET | `/api/v1/schedule-entries` | ADMIN, MANAGER | The roster (shift or week off per date) of the people in scope |
| GET | `/api/v1/me/schedule` | ADMIN, MANAGER, EMPLOYEE | My roster |
| PUT | `/api/v1/employees/{employeeId}/schedule-entries/{date}` | ADMIN | Set one date of the roster (a shift or a week off) |
| DELETE | `/api/v1/employees/{employeeId}/schedule-entries/{date}` | ADMIN | Remove that entry (back to the assigned shift) |
| PUT | `/api/v1/employees/{employeeId}/schedule-entries` | ADMIN | Set up to 62 dates at once |
| GET | `/api/v1/me/time` | ADMIN, MANAGER, EMPLOYEE | My current time state (timer, today's timeline) |
| POST | `/api/v1/me/time-events` | ADMIN, MANAGER, EMPLOYEE | Start work, break, resume, end the day |
| GET | `/api/v1/workdays/mine` | ADMIN, MANAGER, EMPLOYEE | My history: workdays of a date range, totals, daily series |
| GET | `/api/v1/workdays/{workdayId}` | ADMIN, MANAGER, EMPLOYEE | One workday with its work and break intervals: my own, or one of somebody in my scope |
| GET | `/api/v1/team/members` | ADMIN, MANAGER | Live board of the people in the caller's scope |
| GET | `/api/v1/team/members/{employeeId}` | ADMIN, MANAGER | One person of the scope with their current time state |
| GET | `/api/v1/team/members/{employeeId}/workdays` | ADMIN, MANAGER | One person's history (as `GET /api/v1/workdays/mine`) |
| PATCH | `/api/v1/workdays/{workdayId}/work-sessions/{sessionId}` | ADMIN, MANAGER | Correct a work interval of somebody in scope |
| PATCH | `/api/v1/workdays/{workdayId}/break-sessions/{sessionId}` | ADMIN, MANAGER | Correct a break interval of somebody in scope |
| PUT | `/api/v1/workdays/{workdayId}/status` | ADMIN, MANAGER | End an open (forgotten) workday of somebody in scope |
| GET | `/api/v1/reports/workdays` | ADMIN, MANAGER | Workdays of the people in scope for a date range (rows, totals, daily series); with `Accept: text/csv` the CSV file |
| GET | `/api/v1/reports/summaries` | ADMIN, MANAGER | The same workdays added up per employee, per manager of the day, or per date |
| GET | `/api/v1/audit-logs` | ADMIN, SUPER_ADMIN | The audit log (filters, paging); `format=csv` exports it |
| GET | `/api/v1/audit-logs/{auditLogId}` | ADMIN, SUPER_ADMIN | One audit entry in full |
| PUT | `/api/v1/integration/employees` | JurisOne service token only | JurisOne pushes its shifts and employees (roster sync, idempotent) |

## Sign-in (standalone)

### POST /api/v1/auth/otp-requests
Body `{ email: string }`. Always 200 `{ ok, message: "If that account exists and is active, a 6-digit code is on its way to the email." }`; no code is sent
to an unknown or deactivated account, to a person whose login the owner switched off, or to a user of a suspended business. 400 missing/invalid email; 429 too many requests (per IP and per email).
The IP address is the connection's, or, behind a trusted proxy (`juristrack.security.trusted-proxies`, default loopback only), the client it forwarded in
`X-Forwarded-For` (the right-most hop that is not a proxy): a caller cannot choose its own address by writing the header.
Audit: `auth.sign_in_code_requested`, SUCCESS when a code was sent, DENIED with the reason (`unknown_email`, `inactive`, `login_disabled`, `business_suspended`) otherwise
(the answer is the same; a DENIED entry is written once per address, IP and window); a 429 writes `auth.rate_limited` once per limit, key and window.

### POST /api/v1/auth/sessions
Body `{ email: string, otp: string (6 digits), remember?: boolean }` (`remember` = keep me signed in). 201 with the `jt_at` / `jt_rt` cookies and
`data = { id, firstName, lastName, email, role, businessName }` (`businessName` null for the SUPER_ADMIN). 400 malformed code; 401 wrong, expired or used
code, unknown or deactivated account, login switched off, or suspended business (always the same message); 429 after too many failures from one IP address (20 per window) or
for one account (10 per window, from whatever address); only failed attempts count.
Audit: `auth.signed_in`; a refusal writes `auth.sign_in_failed` with the reason (`wrong_code`, `expired_or_used_code`, `code_burnt`, `unknown_email`,
`inactive`, `login_disabled`, `business_suspended`), the wrong try that uses up a code's 5 tries also `auth.sign_in_code_burnt`; a 429 writes `auth.rate_limited` (once per window).

### POST /api/v1/auth/session-refreshes
No body; uses the `jt_rt` cookie. 201 with new cookies. A token used again within `refresh-reuse-grace-seconds` (two requests of one browser at once) gets
a new `jt_at` only, no second `jt_rt` (so a copied token cannot start a parallel chain). 401 (cookies cleared) when the session ended, expired, the user was deactivated or their login switched off, or the refresh
token was used twice. Audit: a successful refresh is not audited (every few minutes); a reused token writes `auth.session_revoked_refresh_reuse`, any
other refusal `auth.session_refresh_failed` (reason `unknown`, `revoked`, `expired`, `inactive`, `login_disabled`; once per reason, IP address, user and window); a
request without the cookie writes nothing.

### DELETE /api/v1/auth/sessions/current
No body. Always 200; ends this device's session and clears the cookies. Audit: `auth.signed_out` when a session was ended (also with only the refresh
cookie; `after.withRefreshCookieOnly`).

## GET /api/v1/me
Any signed-in user. `data = { id, firstName, lastName, email, role, businessName, employeeCode, managerName, businessTimeZone }`.
`businessName` and `businessTimeZone` are null for the SUPER_ADMIN; `employeeCode` and `managerName` ("First Last" of the reporting manager) are null when
not set. 401 signed out, 403 `BUSINESS_SUSPENDED`.

## Businesses (platform super admin)

The SUPER_ADMIN belongs to no business; these requests see every business. A business's **owner** is its earliest-created ADMIN.

### GET /api/v1/businesses
Query: list parameters, `sort` = `name` (default) | `createdAt` | `employeeCount` | `status` (active first when ascending), `status` = `all` (default) |
`active` | `suspended`. `search` matches the business name, the owner's name and the owner's email.
Row: `{ id, name, timeZone, isSuspended, isLinkedToJurisone, ownerId, ownerName, ownerEmail, employeeCount, activeEmployeeCount, createdAt }`
(`owner*` null when the business has no ADMIN, possible for a JurisOne business; `employeeCount` counts every user of the business, owner included).
Audit: `access.business_list_viewed` (platform log; the query parameters, `rowCount`, `total` in `after`), one per request.

### POST /api/v1/businesses
Body:
```json
{ "name": "string, 1-80", "timeZone": "IANA id, optional (default Asia/Kolkata)",
  "owner": { "firstName": "1-60", "lastName": "1-60", "email": "valid, at most 200", "employeeCode": "optional, letters/digits/-/_, at most 32" } }
```
Creates the business (stale workday hours 14, display time zones `Asia/Kolkata`, `America/New_York`) and its owner (role ADMIN, active, no manager) in one
transaction. 201 `{ ok, message: "Business created.", data: { id, ownerId } }`. The owner signs in with an emailed code.
Errors: 400 (fields `name`, `timeZone`, `owner.firstName`, `owner.lastName`, `owner.email`, `owner.employeeCode`), 409 `EMAIL_IN_USE` (the email belongs to
any user of any business or to the super admin; compared lower-cased). Audit: `business.created` and `employee.created` (the owner).

### GET /api/v1/businesses/{businessId}
`data = { id, name, timeZone, staleWorkdayHours, displayTimeZones: string[], isSuspended, isLinkedToJurisone, createdAt, updatedAt,
owner: { id, employeeCode, firstName, lastName, email, isActive, lastLoginAt } | null, employeeCount, activeEmployeeCount }`. 404 unknown.
Audit: `access.business_viewed` in that business's log (subject = its owner), so the owner sees that the platform looked.

### PATCH /api/v1/businesses/{businessId}
Body (each optional): `{ name: string 1-80, timeZone: string, staleWorkdayHours: integer 1-48, displayTimeZones: string[] (at most 8) }`. None may be
`null` (send `[]` for no display zones). Time zones are checked against the IANA database (any case, links such as `Asia/Calcutta`, three-letter ids such
as `IST`, offsets such as `+05:30`) and stored in canonical form; duplicate display zones are dropped. A new time zone applies to workdays started from
then on. 200 `{ ok, message: "Business saved.", data: <as GET /api/v1/businesses/{businessId}> }`. 400 (field names as above, `displayTimeZones[i]` for one
zone), 404. Audit: `business.updated` (only when something changed).

### PUT /api/v1/businesses/{businessId}/status
Body `{ isSuspended: boolean (required), reason?: string (at most 500) }`. 200 `{ ok, message: "Business suspended." | "Business is active.", data: <detail> }`.
While suspended the business's users get no sign-in codes, cannot sign in, and every request of theirs with an existing session (or JurisOne token) answers
403 `BUSINESS_SUSPENDED`; sessions are not ended, so they work again after reactivation. 400 (`isSuspended`, `reason`), 404.
**Suspending closes every open workday of the business** at that moment (nobody can end or correct a day while suspended, so the suspension must not
count as work time): the open work or break interval ends, the day is `DAY_ENDED` with recomputed totals, one `workday.closed` entry per person (reason
`business_suspended`, actor = the super admin). Reactivating reopens nothing.
Audit: `business.suspended` / `business.reactivated` with the reason.

### PUT /api/v1/businesses/{businessId}/jurisone-link
Body `{ jurisoneBusinessId: uuid (required) }`. Links a **standalone** business to its business in JurisOne: from then on the JurisOne tokens of that
business resolve to it and its people are linked by email on their first token (a token never takes the ADMIN role away from an owner). This is the only
way an existing business is linked: a JurisOne token never adopts a business by email (that would let any owner plant an email and take over a company).
200 `{ ok, message: "Business linked to JurisOne.", data: <as GET /api/v1/businesses/{businessId}> }` (`isLinkedToJurisone: true`); the same link again changes
nothing and writes no entry. Errors: 400 (`jurisoneBusinessId` missing or not a UUID), 404, 409 `BUSINESS_ALREADY_LINKED` (linked to another JurisOne
business: remove the link first), 409 `JURISONE_BUSINESS_IN_USE` (another business has that JurisOne id; a JurisOne business that already got a
business of its own by a token is in this state). Audit: `integration.business_linked` (category `integration`, in the business's log, `after.jurisoneBusinessId`).

### DELETE /api/v1/businesses/{businessId}/jurisone-link
No body. Removes the link; the business stays a standalone one and its people keep their accounts (their JurisOne employee ids stay recorded). 200
`{ ok, message: "Business unlinked from JurisOne.", data: <detail> }` (`isLinkedToJurisone: false`); a business that is not linked is left as it is
(nothing written). 404. Audit: `integration.business_unlinked` (`before.jurisoneBusinessId`).

## Business settings (owner)

### GET /api/v1/business
ADMIN. The owner's own business: `data = { id, name, timeZone, staleWorkdayHours, displayTimeZones, createdAt, updatedAt }`. `displayTimeZones` is stored as
is; the business time zone is always offered for display as well (the client adds it).

### PATCH /api/v1/business
ADMIN. Body and rules as `PATCH /api/v1/businesses/{businessId}`. 200 `{ ok, message: "Settings saved.", data: <as GET /api/v1/business> }`. 400. Audit:
`business.updated`.

## Employees (owner)

Every user of the owner's business, the owner included (never the SUPER_ADMIN). A user of another business answers 404 everywhere.

Employee (rows of the list and the single employee):
`{ id, employeeCode, firstName, lastName, email, role, isActive, isLoginEnabled, managerId, managerName, lastLoginAt, createdAt, activeReportCount, shift: { id, name, startTime, endTime } }`
(`isLoginEnabled` = false when the owner switched the person's sign-in off, see `PUT .../login-access`; `managerName` = "First Last" of the manager, `lastLoginAt` null before the first sign-in, `activeReportCount` = active users whose manager this is, `shift` = the shift
assigned today in the business time zone, the default shift "General day" without an assignment; times are `HH:mm`).

### GET /api/v1/employees
Query: list parameters, `sort` = `name` (default; first then last name) | `employeeCode` (empty codes last) | `email` | `role` (ADMIN, MANAGER, EMPLOYEE
when ascending) | `status` (active first when ascending) | `lastLoginAt` (never signed in last); filters `status` = `active` (default) | `inactive` | `all`,
`role` = one or more of `EMPLOYEE`, `MANAGER`, `ADMIN` comma-separated (any case; e.g. `role=MANAGER,ADMIN` for a manager picker), `managerId` = only the
direct reports of that user. `search` matches first name, last name, "first last", email and employee code (`%` and `_` are literal).
Audit: `access.employee_list_viewed`, one per request, with the query parameters, `rowCount` and `total` in `after`.

### POST /api/v1/employees
Body: `{ firstName: 1-60, lastName: 1-60, email: valid, at most 200, employeeCode?: letters/digits/-/_, at most 32, role?: "EMPLOYEE" (default) | "MANAGER",
managerId?: uuid }`. The employee is active at once and signs in with an emailed code. 201 `{ ok, message: "Employee added.", data: { id } }`.
Errors: 400 (fields `firstName`, `lastName`, `email`, `employeeCode`; an unknown role or malformed `managerId` is a 400 without field), 409 `EMAIL_IN_USE`
(any business), 409 `EMPLOYEE_CODE_IN_USE` (this business), 422 `ROLE_NOT_ASSIGNABLE` (role ADMIN or SUPER_ADMIN), 422 manager rules (below).
Audit: `employee.created`; a 422 writes `employee.change_refused` (outcome DENIED, the code as reason) although nothing is created.

### GET /api/v1/employees/{employeeId}
`data = <employee>`. 404. Audit: `access.employee_viewed` (subject = the employee), not when the owner opens their own record.

### PATCH /api/v1/employees/{employeeId}
Body (each optional): `{ employeeCode: string | null, firstName: string, lastName: string, email: string, role: "EMPLOYEE" | "MANAGER", managerId: uuid | null }`.
`null` clears `employeeCode` or `managerId`; `null` for a name, the email or the role is a 400. 200 `{ ok, message: "Employee saved.", data: <employee> }`.
Errors: 400, 404, 409 `EMAIL_IN_USE`, 409 `EMPLOYEE_CODE_IN_USE`, 422 `ROLE_NOT_ASSIGNABLE` (giving ADMIN / SUPER_ADMIN, or changing an ADMIN's role),
422 `MANAGER_HAS_REPORTS` (a MANAGER with active reports cannot become an EMPLOYEE: give the reports another manager first), 422 manager rules.
Audit: `employee.updated` with the whole record before and after (`employeeCode`, `firstName`, `lastName`, `email`, `role`, `isActive`, `managerId`;
only when something changed); a 422 writes `employee.change_refused` (DENIED, the code as reason). A role change applies on the user's next request.

**Manager rules** (create and edit, whenever `managerId` is set to a new value): 422 `MANAGER_NOT_FOUND` (unknown, or a user of another business),
`MANAGER_INACTIVE` (deactivated), `MANAGER_ROLE_REQUIRED` (the manager's role is EMPLOYEE; it must be MANAGER or ADMIN), `MANAGER_IS_SELF`,
`MANAGER_CYCLE` (the chosen manager reports, directly or further down, to this employee).

### PUT /api/v1/employees/{employeeId}/status
Body `{ isActive: boolean (required), reason?: string (at most 500) }`. 200 `{ ok, message: "Employee deactivated." | "Employee is active.", data: <employee> }`.
Deactivating ends every session of the person at once (their next request is 401) and they get no new sign-in codes until reactivated. Their reports keep
them as manager. In the same transaction their open workday (if any) is closed at that moment: the running work or break interval ends, the day is
completed and its totals are recomputed. Errors: 400 (`isActive`, `reason`), 404, 422 `CANNOT_DEACTIVATE_SELF`, 422 `LAST_ACTIVE_OWNER` (the business must
keep one active ADMIN). Audit: `employee.deactivated` (after includes `endedSessions`, and `closedWorkdayId` when a workday was closed) /
`employee.reactivated`, with the reason; a closed workday also writes `workday.closed` (before `{ status, endedAt: null }`, after
`{ status: "COMPLETED", endedAt }`, the reason given, else "Employee deactivated."); a 422 writes `employee.change_refused` (DENIED, the code as reason).

### PUT /api/v1/employees/{employeeId}/shift
Body `{ shiftId: uuid (required), effectiveFrom?: "YYYY-MM-DD" }`; `effectiveFrom` defaults to today in the business time zone, a past date is 422
`SHIFT_EFFECTIVE_DATE_IN_PAST`. The shift applies to every shift date from `effectiveFrom` on (the latest assignment not after a date applies; the
history is kept, no workday is rewritten). 200 `{ ok, message: "Shift saved.", data: <employee> }` (with `shift` = the shift assigned today). Giving
the shift the employee already has on that date changes nothing and writes no audit entry. Errors: 400 (`shiftId`, `effectiveFrom`), 404, 422
`SHIFT_NOT_FOUND` (unknown or another business's shift), 422 `SHIFT_INACTIVE`. Audit: `employee.shift_changed` (subject = the employee; before
`{ shiftId, shiftName }` of that date, after `{ shiftId, shiftName, effectiveFrom }`); a 422 writes `employee.change_refused`.
### PUT /api/v1/employees/{employeeId}/login-access
Switches a person's sign-in off or on **without deactivating them**: they stay active, in the list, on the board and in the reports, and their history is kept;
they only cannot sign in. Body `{ isEnabled: boolean (required), reason?: string (at most 500) }`. 200 `{ ok, message: "Login disabled." | "Login enabled.",
data: <employee> }`. Idempotent: setting the state the person already has changes nothing and writes no audit entry. Only the owner (ADMIN) of the
person's business may call it (MANAGER and EMPLOYEE 403; another business's person, the SUPER_ADMIN and an unknown id 404).
Switching off (a) ends every session of the person at once (their next request is 401; a refresh is refused too), (b) closes their open workday at that moment
exactly as a deactivation does (the running interval ends, the day is completed and its totals are recomputed), (c) makes a sign-in code request and a code check
answer exactly as for an unknown account (200 with the usual text, 401 "That code is wrong or has expired. Request a new one."; no code is sent), and (d)
refuses a JurisOne token of that person with 403 `FORBIDDEN` (audited `integration.user_refused`, reason `login_disabled`). Switching on restores sign-in
only; the closed workday is not reopened. Errors: 400 (`isEnabled`, `reason`), 404, 422 `CANNOT_DISABLE_OWN_LOGIN` (the owner cannot switch off their own
sign-in; switching one's own on is a no-op 200).
Audit: `employee.login_disabled` (before `{ isLoginEnabled: true }`, after includes `endedSessions`, and `closedWorkdayId` when a workday was closed) /
`employee.login_enabled`, with the reason; a closed workday also writes `workday.closed` (reason `login_disabled`); a 422 writes `employee.change_refused`
(DENIED, the code as reason). A refused sign-in or code request of a login-disabled person is audited `auth.sign_in_failed` / `auth.sign_in_code_requested`
with the reason `login_disabled`.

## Time tracking (own)

Every user of a business (ADMIN, MANAGER, EMPLOYEE) tracks their own time; the SUPER_ADMIN gets 403 `FORBIDDEN` on every endpoint below. The employee is
always the signed-in user (an id in the body is ignored). A suspended business answers 403 `BUSINESS_SUSPENDED`, a deactivated user is signed out (401; 403 with a JurisOne token).
All times are UTC instants; dates (`YYYY-MM-DD`) are calendar dates in the business time zone; durations are whole seconds computed from the recorded
intervals (closed: end - start; running: now - start). Labels and formatting are the client's.

**States**: `NOT_STARTED` -> `START` -> `WORKING` <-> (`BREAK` / `RESUME`) `ON_BREAK`; `WORKING` -> `END` -> `DAY_ENDED`. **Time is calculated per shift,
not per calendar day** (section "Shifts and the roster"): a workday belongs to the *shift occurrence* its `START` fell into and its `date` is that
occurrence's shift date (for the default shift "General day" that is the calendar date in the business time zone, so a business without shifts behaves
as before). A US shift 19:30-04:30 is one workday of nine scheduled hours whichever calendar dates it touches and keeps the date it started on; the
next or previous shift's work is never added to it. One workday per person and shift date. `START` after the day ended **in the same shift
occurrence** **reopens** that workday with a new work session; the gap between the end and the new start counts as neither productive nor break time
(it stays inside `spanSeconds`); a `START` in another occurrence creates a new workday. `BREAK`, `RESUME` and `END` act on the open workday whatever its
date. A break must be ended with `RESUME` before `END`.

**Workday** (snapshot; `GET /api/v1/me/time` as `workday`, `GET /api/v1/workdays/{workdayId}` as `data`):
`{ id, date, status: "WORKING" | "ON_BREAK" | "DAY_ENDED", startedAt, endedAt | null, isStale, closedProductiveSeconds, closedBreakSeconds,
activeWorkStartedAt | null, activeBreakStartedAt | null, productiveSeconds, breakSeconds, spanSeconds, breakCount,
lastBreak: { startedAt, endedAt | null } | null,
lastActivity: { type: "STARTED_WORK" | "STARTED_BREAK" | "RESUMED_WORK" | "ENDED_DAY", at }, segments: [{ id, type: "WORK" | "BREAK", startedAt, endedAt | null }],
shift: { id | null, name, startTime, endTime, scheduledStartAt, scheduledEndAt, scheduledSeconds } | null, isOutsideShift, isOffDay, varianceSeconds | null,
extraSeconds }`.
`date` is the **shift date**. `shift` is the snapshot taken when the day started (renaming or changing the shift later does not change it; `startTime` /
`endTime` are `HH:mm` on the business zone's wall clock, `scheduledSeconds` the real length of that occurrence: a DST change makes it differ from the
nominal length; null only for a record written before shifts existed). `isOutsideShift`: the `START` was in no occurrence's attribution window (a gap
between shifts or a week off): the work is allowed and counted, never refused, and flagged. `isOffDay`: the day was started on a week off of the roster.
`extraSeconds`: the part of the productive time outside the scheduled window of the occurrence (all of it on a week off; 0 for the 24-hour default
shift); it is included in `productiveSeconds`, never added to it. `varianceSeconds` = `productiveSeconds` - `scheduledSeconds` once the day has ended
(`productiveSeconds` on a week off), else null.
`productiveSeconds` / `breakSeconds` include the running interval up to `serverTime`; a client keeps a timer running with
`closedProductiveSeconds + (now - activeWorkStartedAt)` and `closedBreakSeconds + (now - activeBreakStartedAt)`. `spanSeconds` = start of the day to its end
(or now). `breakCount` counts the running break too. `lastBreak` is the break that started last (`endedAt` null while it runs), null when the day has no break. `isStale` ("not ended") = the day is still open and its last uninterrupted stretch (from the start, or from
the last time it was started again after the day had ended) is more than the business's
`staleWorkdayHours` (computed when read), so a day reopened late in the evening is not "not ended" because of the morning. `segments` are oldest first.
**Precision**: events are stamped to the millisecond (what a browser keeps), never before the latest instant already recorded for the person (a clock that
steps back, or an instance whose clock runs behind, cannot put an interval before the previous one: the event is stamped at that latest instant).

### GET /api/v1/me/time
`data = { employeeId, serverTime, today, businessTimeZone, staleWorkdayHours, displayTimeZones: string[], status: "NOT_STARTED" | "WORKING" | "ON_BREAK" |
"DAY_ENDED", shiftDate, isOffDay, shift, workday | null }`. `shiftDate` is the date of the shift occurrence "now" belongs to for this person (the calendar
date for the default shift; `today` stays the calendar date), `isOffDay` whether the roster has a week off for it, `shift` that occurrence's shift as in
the workday snapshot (so the timer can show "Shift 19:30-04:30" before the day starts). `displayTimeZones` is the business's configured list (IANA ids, as stored; see `GET /api/v1/business`) for every signed-in
user's time-zone switcher; the business time zone is always offered as well (the client adds it). It is returned only here and in the `state` of
`POST /api/v1/me/time-events`; the history and workday endpoints do not repeat it.
`workday` is the **current workday**: the open workday (whatever its date), else the workday of the shift occurrence "now" belongs to (`DAY_ENDED`, with
its own `date`: a night shift that ended after midnight stays the current workday until a later occurrence is nearer), else null (`status` NOT_STARTED).
Which occurrence "now" belongs to: see "Shifts and the roster". `serverTime` lets the client measure its clock offset.

### POST /api/v1/me/time-events
Body `{ type: "START" | "BREAK" | "RESUME" | "END" }` (any case). Answer `data = { type, alreadyApplied, workdayId, state: <as GET /api/v1/me/time> }`:
- **201** when the event changed the state, with `message` "Workday started. Have a productive day." / "Break started. Your productive timer is paused." /
  "Welcome back. Your productive timer is running." / "Workday ended. Your summary is ready." (`alreadyApplied` false);
- **200** without `message` when the person already is where the event leads (a double click, a retry, another device): nothing is created or changed
  and `alreadyApplied` is true. Repeats: `START` while working or on a break, `BREAK` while on a break, `RESUME` while working, `END` after the day ended
  (also after midnight: a repeated `END` of a night shift is a repeat, not a 409, and writes no audit entry).

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
`TIME_RECORD_INCONSISTENT` writes `time.event_failed` (outcome FAILED, `after.type`). `GET /api/v1/me/time` (polled) is never audited.

### GET /api/v1/workdays/mine
The signed-in person's workdays dated in a range. Query: `range` = `today` | `yesterday` | `week` (Monday to today) | `month` (default; the 1st to today) |
`custom` with `from` / `to` (`YYYY-MM-DD`; a missing `from` is this week's Monday, a missing `to` today, reversed ends are swapped, at most 366 days: an
earlier `from` is moved up); `sort` = `date` (default) | `startedAt` | `endedAt` (open days last when ascending) | `productiveSeconds` | `breakSeconds` |
`spanSeconds` | `breakCount`; `sortDirection` (**default `desc` here**: newest first), `page`, `pageSize` (list parameters; `search` is ignored).
"Today" is the person's **current shift date** (the date of the shift occurrence they are in now; the calendar date in the business time zone for the
default shift), so a night shift that runs past midnight, or just ended, is in "Today"; `week` and `month` end on it. 400 for an unknown `range`, a malformed `from` / `to` or a bad list parameter.
`data = { rows, total, page, pageSize, range, from, to, totals, dailySeries }`:
- row `{ id, date, status, isStale, startedAt, endedAt | null, productiveSeconds, breakSeconds, spanSeconds, breakCount,
  lastBreak: { startedAt, endedAt | null } | null, shift, isOutsideShift, isOffDay,
  varianceSeconds | null, extraSeconds }` (`date` = shift date; `shift` etc. as in the workday snapshot; `lastBreak` = the break that started last, null without a break; an open day's figures run to
  `now`; an ended day's come from the totals stored when it ended);
- `totals = { workdays, productiveSeconds, breakSeconds, spanSeconds, breakCount, averageProductiveSeconds, extraSeconds }` over the whole range (every page);
  `averageProductiveSeconds` = productive seconds per workday of the range, rounded half up, 0 without workdays (see "Averages");
- `dailySeries = [{ date, productiveSeconds, breakSeconds, workdays }]`, one point for every date from `from` to `to` (zero when nothing was recorded).

### GET /api/v1/workdays/{workdayId}
`data = <workday>` with every work and break interval. One's own workdays, and (MANAGER, ADMIN) a workday of anybody in the caller's scope (section
"Who sees and corrects whom"). Anybody else's, another business's and an unknown id answer 404 `NOT_FOUND` ("Workday not found."); 400 for a malformed
id. Reading somebody else's workday is audited (`access.workday_viewed`, subject = the person, entity `workday`, after `{ date }`); reading one's own
is not.

## Team tab (managers and the owner)

EMPLOYEE and SUPER_ADMIN get 403 `FORBIDDEN` on every Team and correction endpoint; signed out 401.

### Who sees and corrects whom (scope)

The reporting line (`users.manager_id`) decides, read when the request runs (not the manager stored on old workdays), through
`ReportingScopeService` (depth 1 = direct reports, depth 2 = also their reports; never the caller, nobody above or beside them; cycle-safe):

| Caller | Board (`GET /api/v1/team/members`) and reports (`scope`) | One person, their history, their workdays, corrections |
|---|---|---|
| MANAGER | `scope=direct` (default): their direct reports; `scope=indirect`: also the reports of those (one level further down) | direct and indirect reports (depth 2), whatever the board's switch shows |
| ADMIN (owner) | `scope=all` (default): everybody of the business; `scope=direct` / `indirect`: their own reporting line as for a manager | everybody of the business |

- The caller is never in their own list or scope: their own record is the personal section (`/api/v1/me/time`, `/api/v1/workdays/mine`), and
  `GET /api/v1/team/members/{ownId}` answers 404.
- People above or beside a manager, people of another business, unknown ids and out-of-scope ids all answer **404** `NOT_FOUND` (never 403), so a
  caller cannot learn that a record exists.
- **Nobody corrects their own time**: 403 `CANNOT_CORRECT_OWN_TIME` ("You cannot correct your own time. Ask your manager or the owner to do it."), also
  for the owner. A manager's time is corrected by their own manager or the owner.
- Deactivated people stay in the scope for reading and correcting their past days (their history stays visible); the board leaves them out (below).

### GET /api/v1/team/members
Query:
- `scope` = `direct` | `indirect` (MANAGER, default `direct`) or `all` | `direct` | `indirect` (ADMIN, default `all`); any case. Anything else (also
  `all` for a manager) is 400 with field `scope`.
- `status` = `WORKING` | `ON_BREAK` | `NOT_STARTED` | `DAY_ENDED` | `all` (default; any case); else 400 (field `status`).
- `search`: first name, last name, "first last", email or employee code contains the text (case-insensitive).
- `sort` = `name` (default; first then last name) | `status` (ascending: WORKING, ON_BREAK, NOT_STARTED, DAY_ENDED) | `productiveSeconds` |
  `lastActivity` (people without activity first when ascending); `sortDirection` (`asc` default | `desc`); ties are ordered by name.
- `page`, `pageSize` (list parameters).

Who is on the board: the people of the scope who are **active**, plus a deactivated person whose workday is still open (should not happen:
deactivation closes it). Each person's state is decided exactly as `GET /api/v1/me/time` decides it for oneself: the open workday whatever its date (a
night shift that started yesterday is `WORKING` with yesterday's `date`), else the workday of the shift occurrence the person is in now (`DAY_ENDED`
with its own shift `date`, also after midnight), else `NOT_STARTED`. A person whose roster has a week off for their current shift date and who has no
workday is `NOT_STARTED` in the row (`isOffDay` true) but is counted in `metrics.weekOff`, not in `metrics.notStarted`.

`data = { rows, total, page, pageSize, scope, serverTime, today, businessTimeZone, staleWorkdayHours, metrics }`:
- `total` = rows matching `status` and `search` (all pages); `scope` = the scope used (lower case); `today` = `YYYY-MM-DD` in the business time zone;
  `serverTime` lets the client measure its clock offset.
- row: `{ employee: { id, employeeCode, firstName, lastName, email, role, isActive, managerId, managerName }, status, workdayId | null, date | null,
  startedAt | null, endedAt | null, isStale, closedProductiveSeconds, closedBreakSeconds, activeWorkStartedAt | null, activeBreakStartedAt | null,
  productiveSeconds, breakSeconds, spanSeconds, breakCount, lastBreak: { startedAt, endedAt | null } | null, lastActivity: { type, at } | null, shift, isOutsideShift, isOffDay, varianceSeconds | null,
  extraSeconds }` (`shift` = the workday's shift, without a workday the shift occurrence the person is in now). The time fields mean exactly what they mean in the
  workday snapshot (live values up to `serverTime`), so the client runs the same live timer. `NOT_STARTED` rows have every number 0 and every time /
  id null. `managerName` = "First Last" of the person's manager, null without one.
- `metrics = { total, working, onBreak, notStarted, dayEnded, stale, totalProductiveSeconds, totalBreakSeconds, averageProductiveSeconds }` over
  **everybody on the board in the chosen scope**: `status`, `search` and paging do not change them. `averageProductiveSeconds`: see "Averages" below.

The board is read in batches (people, workdays, work intervals, break intervals: one query each), whatever its size. Reading the board is **not**
audited (it refreshes every few seconds and shows only today's state); opening one person is.

### GET /api/v1/team/members/{employeeId}
`data = { employee: <as in the board row>, serverTime, today, businessTimeZone, staleWorkdayHours, status, workday | null }`, where `status` and
`workday` (the full workday snapshot with `segments`) are chosen as `GET /api/v1/me/time` chooses them for oneself. A deactivated person can be opened.
404 `NOT_FOUND` ("Employee not found.") outside the scope; 400 for a malformed id.
Audit (access): `access.team_member_viewed` (actor = caller, subject = the person, entity `user`), one per request.

### GET /api/v1/team/members/{employeeId}/workdays
The person's workdays with the query parameters, sorting, defaults (`sortDirection` default `desc`) and answer of `GET /api/v1/workdays/mine`:
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
- **Window**: a corrected time (and the end of a closed workday) must lie from the start of the workday's shift date to the end of the **next** date, in
  the zone the workday was started in (a night shift may run into the next morning; a shift whose scheduled window plus 12 hours reaches further widens
  the window to that), and the first interval must start on the shift date itself; else 422 `CORRECTION_OUT_OF_RANGE` ("Choose a time on {date} or
  {date+1}, in the business time zone. To close a forgotten shift, give the time it really ended."; closing with the default "now" long after the
  window answers it too, and the screen then offers the last activity as the end). A year typed wrong, or year 1, can no longer add years of work or move hours into another day.
- **Other workdays**: the corrected workday (its first start to its last end, or to now while it runs) must not overlap another workday of the same person
  (422 `WORKDAY_OVERLAP`: the same hours would count twice in the reports); touching is fine.
- A running interval keeps running: only its start moves (`endedAt` left out or `null`); a value for `endedAt` is 422 `INTERVAL_STILL_RUNNING`
  ("This interval is still running. Close the workday instead of setting an end time."). A closed interval stays closed (`endedAt: null` is 400
  "An end time is required.").
- The workday's `startedAt` becomes the start of its first interval and, when it has ended, its `endedAt` the end of its last interval. Its `date`
  never changes. A `DAY_ENDED` workday stays ended. Its stored totals are recomputed from the intervals (so they always equal the sum of the
  intervals); an open workday keeps none (it is computed live).
- Each correction locks the employee's row first, like `POST /api/v1/me/time-events`, so it never interleaves with the employee's own start, break,
  resume or end; the state is read after the lock.

Error order: 401 / 403 role, then 400 for the body, then 404 (unknown workday, another business, out of scope) or 403 `CANNOT_CORRECT_OWN_TIME`,
then 404 for the interval, then the rules above (400 / 422). A 422 is audited as `time.correction_refused` (outcome DENIED, the code as reason,
`after.operation` = `adjust_work_session` | `adjust_break_session` | `close_workday`), written after the rollback.

### PATCH /api/v1/workdays/{workdayId}/work-sessions/{sessionId}
### PATCH /api/v1/workdays/{workdayId}/break-sessions/{sessionId}
Body: `{ startedAt?: string, endedAt?: string | null, reason: string }` (PATCH: a field left out stays as it is; `startedAt: null` is 400).
200 `{ ok, message: "Time corrected.", data: <workday> }`; when the times equal the stored ones nothing is written and the answer is 200 with
`message: "Nothing changed."`. 404 "Time record not found." when the interval does not exist, belongs to another workday or is of the other type
(a break id on `work-sessions`). Other errors as above.
Audit: `time.session_adjusted`, actor = caller, subject = the employee, entity `work_session` / `break_session` with the interval's id, the reason,
before / after `{ interval: { id, type: "WORK" | "BREAK", startedAt, endedAt }, workday: { id, date, status, startedAt, endedAt, productiveSeconds,
breakSeconds, spanSeconds, breakCount } }` (an open day's totals as of the correction).

### PUT /api/v1/workdays/{workdayId}/status
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

## Shifts and the roster

**Why.** Offshore teams work US or UK shifts from India: a US shift 19:30-04:30 or a UK shift 14:00-23:00 is one workday of nine hours, whichever
calendar dates it touches, and the next or previous shift's work is never added to it. JurisTrack therefore calculates **per shift**, not per
calendar day (reference: Time Doctor, which counts "Worked today" per calendar day; this is a deliberate difference). Durations stay computed from the
recorded intervals only.

**Shift** (a template of the business): `{ id, name, startTime, endTime, crossesMidnight, scheduledMinutes, earlyStartMinutes, lateEndMinutes,
isDefault, isActive, employeeCount }`. `startTime` / `endTime` are `HH:mm` on the **business time zone's** wall clock; an end at or before the start
means the shift ends on the next day (start = end is 24 hours); `scheduledMinutes` is the nominal length (a real occurrence differs by a DST change).
`earlyStartMinutes` / `lateEndMinutes` (0 to 720, default 180) are the grace in which a `START` still belongs to the shift. Every business has exactly
one default shift, **"General day"** (00:00-00:00, grace 0/0): it is the calendar day, i.e. the behaviour before shifts existed, and applies to everybody
without an assignment. It cannot be deactivated and keeps its times (only the name can change). `employeeCount` = active employees whose latest
assignment is this shift (the default shift also counts those without any).

**Occurrence and attribution.** The occurrence of shift S on shift date D has the scheduled window [D + start, D + end (+1 day when it crosses
midnight)] in the business zone (DST-aware) and the attribution window [scheduled start - early, scheduled end + late). For an instant t (a `START`, or
"now" when reading) the effective schedule of each date from D-2 to D+1 gives that date's shift (see "Roster" below); then:
1. the occurrence whose attribution window contains t; if several, the one whose scheduled start is nearest (the earlier date on a tie);
2. else, if t's calendar date is a **week off**, the occurrence the assigned shift would have had that date: `isOffDay` and `isOutsideShift` are true;
3. else (a gap between shifts) the occurrence nearest to t (by distance to its scheduled window): `isOutsideShift` is true.
Work is never refused: outside work is allowed, flagged and counted (as extra time). For the default shift this is simply the calendar date.
Consecutive occurrences never share a workday (their attribution windows do not overlap unless the grace is very large; then the nearest start wins).

**Workday identity.** One workday per person and **shift date** (`date`); a `START` whose occurrence already has a workday reopens it, otherwise a new
workday is created with the snapshot (`shift`, `isOutsideShift`, `isOffDay`). `BREAK` / `RESUME` / `END` act on the open workday whatever its date.
Reads ("current workday") use the same occurrence: the open workday, else the workday of the occurrence "now" belongs to, else none (`NOT_STARTED`).

**Extra time.** `extraSeconds` of a workday = productive seconds outside the scheduled window of its occurrence (all of them on a week off; 0 for the
default 24-hour shift). Shown separately, included in productive time, added up in the reports' `totals.extraSeconds` and the board's
`metrics.totalExtraSeconds`. No pay rules.

**Roster (schedule entries).** The effective schedule of an employee for a date is: the **roster entry** for that date if there is one (a shift, or a
week off when `shiftId` is null), else the shift **assigned** to the employee from the latest assignment effective by that date (`PUT
/api/v1/employees/{id}/shift`), else the business's default shift. Weeks off are rotational, so they are stored per date. A date can be set up to 365 days
ahead; a date on which the employee already has a workday cannot change.

### GET /api/v1/shifts
All signed-in roles of the business. Query: `isActive` = `true` | `false` (else 400). `data = [<shift>]`, the default shift first, then by start time.

### POST /api/v1/shifts
ADMIN. Body `{ name: 1-60 (unique in the business, any case), startTime: "HH:mm", endTime: "HH:mm", earlyStartMinutes?: 0-720, lateEndMinutes?: 0-720 }`
(grace default 180). 201 `{ ok, message: "Shift added.", data: { id } }`. Errors: 400 (field per bad input), 409 `SHIFT_NAME_IN_USE`. Audit: `shift.created`.

### GET /api/v1/shifts/{shiftId}
`data = <shift>`; 404 for an unknown id or another business's shift.

### PATCH /api/v1/shifts/{shiftId}
ADMIN. Body (each optional): `{ name, startTime, endTime, earlyStartMinutes, lateEndMinutes }`. Changing times or grace affects **future occurrences
only** (every workday keeps its snapshot). The default shift: only `name` can change (else 400). 200 `{ ok, message: "Shift saved.", data: <shift> }`.
Audit: `shift.updated` (before / after; nothing changed, nothing audited).

### PUT /api/v1/shifts/{shiftId}/status
ADMIN. Body `{ isActive: boolean }`. 422 `DEFAULT_SHIFT_REQUIRED` (the default shift), 422 `SHIFT_IN_USE` (active employees still work it: their latest
assignment is this shift). A deactivated shift cannot be assigned or put on a roster (422 `SHIFT_INACTIVE`); existing workdays are untouched.
Idempotent. 200 `{ ok, message: "Shift deactivated." | "Shift is active.", data: <shift> }`. Audit: `shift.status_changed`.

### GET /api/v1/schedule-entries
ADMIN (everybody of the business except themselves) and MANAGER (direct and indirect reports); EMPLOYEE 403. Query:
`from`, `to` (`YYYY-MM-DD`; default today and the 6 days after; at most 62 days, else 400), `employeeId`, `managerId` (direct reports of that user),
`search`, `page`, `pageSize` (list parameters; sorted by name). Only active people.
`data = { rows, total, page, pageSize, from, to }`, row `{ employeeId, name, employeeCode | null, shift: { id, name, startTime, endTime }, days: [{ date,
shiftId | null, shiftName | null, isOff, source: "ENTRY" | "ASSIGNMENT" | "DEFAULT", reason | null }] }`. `shift` = the shift assigned today; a day with
`isOff` true has `shiftId` and `shiftName` null; `source` says where the day's schedule comes from.

### GET /api/v1/me/schedule
The caller's own row (same query `from`, `to`, same row shape without paging): `data = { employeeId, name, employeeCode, shift, days }`.

### PUT /api/v1/employees/{employeeId}/schedule-entries/{date}
ADMIN. Body `{ shiftId: uuid | null (null = week off), reason?: string (at most 500) }`; idempotent upsert. 200 `{ ok, message: "Schedule saved.", data: <day> }`
(`<day>` as a `days` element). Errors: 400 (malformed date), 404 (employee), 422 `SHIFT_NOT_FOUND` / `SHIFT_INACTIVE`, 422 `SCHEDULE_DATE_TOO_FAR`
(more than 365 days ahead), 422 `SCHEDULE_DATE_HAS_WORKDAY` (the employee has a workday on that date; past dates without a workday are allowed).
Audit: `schedule.entry_set` (subject = the employee, entity id `<employeeId>/<date>`, before / after `{ date, shiftId, isOff, reason }`); setting the
value the date already has writes nothing.

### DELETE /api/v1/employees/{employeeId}/schedule-entries/{date}
ADMIN. Removes the entry: the date follows the assignment again. 200 `{ ok, data: <day> }` (the day as now scheduled); removing a missing entry changes
nothing. 422 `SCHEDULE_DATE_HAS_WORKDAY` as above. Audit: `schedule.entry_removed` (before).

### PUT /api/v1/employees/{employeeId}/schedule-entries
ADMIN. Body `{ entries: [{ date, shiftId | null, reason? }] }`, 1 to 62 entries, each date once (else 400); all or none. Idempotent; **one audit entry
per changed date**. 200 `{ ok, message: "Schedule saved.", data: [<day>] }` in the order sent.

### Open points
Open (owner has not decided): overtime pay rules, work outside the grace being refused instead of flagged, default grace values, and who is master of the roster while both JurisOne and the owner's screens can edit it (until decided, the next push wins for the shifts and dates it lists).

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

A workday belongs to its **shift date** (the date of the shift occurrence it started in; the calendar date it started on for the default shift): the
range filters and the daily series use that date. `range=today` is the calendar date. 400 `VALIDATION_FAILED` with `errors[].field` for every bad parameter at
once (`range`, `from`, `to`, `scope`, `employeeId`, `managerId`, `status`, `groupBy`, `sort`, `sortDirection`, `page`, `pageSize`, and `timeZone` for
the CSV). Order of checks: 400, then 403 (`scope=all` for a manager), then 404 (`employeeId`, `managerId`); the CSV's `timeZone` (400) and row cap
(422) after these.

**Figures**: an ended workday shows the totals stored when it ended (`spanSeconds` = end - start); an open one is computed from its intervals up to the
request time. Every figure covers the whole range (all pages). `totals = { workdays, employees, productiveSeconds, breakSeconds, spanSeconds,
breakCount, averageProductiveSeconds, extraSeconds }` (`employees` = different people; `extraSeconds` = extra time, included in `productiveSeconds`). `dailySeries = [{ date, productiveSeconds, breakSeconds, workdays }]`, one
point for every date from `from` to `to`; the series and the totals always add up to the rows. The range is read set-based (one query for the scope,
one for the workdays with employee and manager joined, one each for the intervals of the open days; index `workdays (business_id, work_date)`, V11).

### GET /api/v1/reports/workdays
Sort keys: `date` (default), `employee` (name, case-insensitive), `startedAt`, `productiveSeconds`, `breakSeconds`, `spanSeconds`, `breakCount`.
**Default `sortDirection` is `desc`** (newest first). Ties: date (newest first), employee name, start, id.
`data = { rows, total, page, pageSize, range, from, to, scope, totals, dailySeries }`, row: `{ id, date, employee: { id, employeeCode | null, name },
manager: { id, name } | null, status: "WORKING" | "ON_BREAK" | "DAY_ENDED", isStale, startedAt, endedAt | null, productiveSeconds, breakSeconds,
spanSeconds, breakCount, lastBreak: { startedAt, endedAt | null } | null, shift, isOutsideShift, isOffDay, varianceSeconds | null, extraSeconds }`. `manager` is the employee's manager **when the workday started** (snapshot), with that person's current name.
Audit (access): `reports.viewed`, entity `report` / `workdays`, subject = the `employeeId` filter (else null), after `{ report, filters, rowCount }`
(`filters = { range, from, to, scope, employeeId, managerId, status: [...], search, sort, sortDirection }`, resolved values); one per request, never one
per row.

**CSV**: the same resource with `Accept: text/csv` (any quality; no header, `*/*` or `application/json` give the JSON). Same parameters, scope and
checks; **every row of the range** in the requested order (paging ignored). One more parameter, `timeZone` = the business time zone (default) or one of
its `displayTimeZones` (IANA id, any case; anything else 400 field `timeZone`). Answer 200, `text/csv;charset=UTF-8`,
`Content-Disposition: attachment; filename="juristrack-<from>-to-<to>.csv"`, `Cache-Control: no-store`; errors stay `application/problem+json`.
File (as the old app's export): UTF-8 with a byte order mark, CRLF, header `Date,Employee,Employee ID,Manager,Status,Work started (<abbr>),End (<abbr>),
Productive hours,Break hours,Span hours,Breaks,Last break (<abbr>),Extra hours,Shift,Scheduled start (<abbr>),Scheduled end (<abbr>),Outside shift,Week off` (`<abbr>` = the display zone's abbreviation at the moment of the export, from `engine/ZoneNames`), rows
with the date, "First Last", employee code, manager of the day, status (`Working`, `On Break`, `Day Ended`, `Not ended` for a stale open day), work started, end and last break (the start of the break that started last, empty without one) as `HH:mm` in the display zone; an end or
last break on another calendar date than the day's start (a night shift) is written `yyyy-MM-dd HH:mm`, hours with two decimals (`toFixed(2)`), breaks, the last break, extra hours, the shift's name and its scheduled start and end `HH:mm`
(empty for a workday without a shift snapshot), `Yes` or empty for "outside shift" and "week off" (column order: the existing columns with "Work started" in place of "Start", then "Last break", then the shift columns). A cell starting with `=`, `+`, `-`, `@`, tab or CR gets a leading `'`;
a cell with `"`, `,`, LF or CR is quoted. More than `juristrack.reports.maximum-export-rows` rows (default 50000) is 422 `EXPORT_TOO_LARGE` ("This
export has N rows; at most M fit in one file. Choose a shorter date range or fewer people."), nothing exported or audited.
Audit (access): `reports.exported`, after `{ report, filters, rowCount, timeZone, fileName }`.

### GET /api/v1/reports/summaries
`groupBy` = `employee` (default) | `manager` (the manager of the day; workdays without one form the group with key `none`) | `date`. Sort keys:
`label` (default; case-insensitive; the `none` group last in both directions), `workdays`, `employees`, `productiveSeconds`, `breakSeconds`,
`spanSeconds`, `breakCount`, `averageProductiveSeconds`. Default `sortDirection`: `asc`, but `desc` for `groupBy=date`. Ties: label, key.
`data = { rows, total, page, pageSize, groupBy, range, from, to, scope, totals, dailySeries }`, row: `{ key, label | null, employeeCode | null,
workdays, employees, productiveSeconds, breakSeconds, spanSeconds, breakCount, averageProductiveSeconds, firstStartedAt, lastEndedAt | null, extraSeconds }`
(`key` = employee id / manager id / `none` / date; `label` = name / date, null for `none`; `employeeCode` only for `groupBy=employee`).
Audit (access): `reports.viewed`, entity `report` / `summaries`, `filters.groupBy` set, `rowCount` = groups.

A report filtered on the caller themselves (`employeeId` = own id, owner only) is reading one's own data and is not audited.

### Averages
- Board `metrics.averageProductiveSeconds` = `totalProductiveSeconds` divided by the people who started today (working, on a break or day ended),
  rounded down; 0 when nobody started.
- Report `totals.averageProductiveSeconds`, a summary row's `averageProductiveSeconds` and the history's `totals.averageProductiveSeconds` (`GET /api/v1/workdays/mine`,
  `GET /api/v1/team/members/{employeeId}/workdays`) = productive seconds per workday of the range, rounded half up; 0 without
  workdays.

## Roster sync (JurisOne service token)

### PUT /api/v1/integration/employees
JurisOne pushes its employees and shifts so that a manager sees every report before those people ever opened JurisTrack, each person is on the right
shift, and people deactivated in JurisOne are deactivated here. Idempotent; one business per call; any subset of the business's employees is fine (the
ones not sent are left untouched). Only while `juristrack.integration.enabled=true`.

**Caller: JurisOne's service token only.** `Authorization: Bearer <service JWT>`, signed with JurisOne's private key (the same RS256 / ES256 key, issuer
and audience checks, 10-minute maximum lifetime, clock skew and single-use `jti` replay guard as a user token). Claims: `iss` (the configured issuer),
`aud` = `juristrack`, `scope` = `employees:sync` (required, exactly this value), `businessId` (the JurisOne business UUID), `businessName`, `iat`, `exp`
(`exp - iat` at most 10 minutes), `jti`. It has **no** `sub` and no `email` (a token with either is refused: it is not a service token). A user token, a
cookie session (403 `FORBIDDEN`) or a token with another scope cannot call it, and a service token opens **no other endpoint** (a user token carrying a
`scope` is refused everywhere). An invalid token answers 401 `UNAUTHENTICATED` without details and is audited as `integration.token_rejected` (reason
code only: `missing_scope`, `wrong_scope`, `not_a_service_token`, `service_token_not_accepted`, `replayed_token`, `expired`, ...). No `Origin` header is
needed. The request body may be up to 16 MB (5000 employees with their schedules); every other endpoint refuses more than 64 KB.

Body (`shifts` and `employees` may each be absent or empty):
```json
{
  "shifts": [ { "code": "US", "name": "US shift", "startTime": "19:30", "endTime": "04:30", "earlyStartMinutes": 180, "lateEndMinutes": 180 } ],
  "employees": [ { "employeeId": "<JurisOne employee id>", "email": "a@b.c", "firstName": "A", "lastName": "B", "employeeCode": "E1", "role": "EMPLOYEE",
                   "managerEmployeeId": "<JurisOne employee id>", "isActive": true, "shiftCode": "US",
                   "schedule": [ { "date": "2026-11-02", "shiftCode": null }, { "date": "2026-11-03", "shiftCode": "UK" } ] } ]
}
```
- **Business**: found by `businesses.jurisone_business_id`, else created (name from `businessName`, the usual defaults and default shift), its name refreshed
  (`integration.business_updated`). **An existing standalone business is never adopted** (it moves to JurisOne only through the super admin's link): a new
  business is created and a person whose email already belongs to another business is skipped. A suspended business: 403 `BUSINESS_SUSPENDED`, nothing changes.
- **Shifts** are upserted by `code` (1 to 40 characters, unique per business: `shifts.code`): created, or changed (name, times, grace: future occurrences
  only, as everywhere); a shift missing from a later call is left as it is. `startTime` / `endTime` are `HH:mm` on the business time zone's wall clock; the
  grace defaults to 180 (0 to 720). A shift the owner made by hand with the same name (any case) and no code is **adopted** (the code is put on it and
  JurisOne's times win). A shift that cannot be applied is listed in `skippedShifts` with `shift_name_in_use` (the name belongs to the default shift or to
  a shift with another code); the employees that name it are then skipped with `unknown_shift_code`. A deactivated shift stays deactivated.
- **Employees** are matched by `users.jurisone_employee_id`, else by email inside the business (an unlinked person added earlier is linked), else
  created. `email` (lower-cased), `firstName` (required), `lastName` (optional), `employeeCode` (letters, digits, `-`, `_`; unique in the business; absent
  leaves the stored one), `role` (`MANAGER` or `EMPLOYEE`; absent = `EMPLOYEE` for a new person and unchanged for an existing one; **never `ADMIN`**, and
  an existing `ADMIN` stays the owner; user tokens remain authoritative for the role on every later call), `isActive` (absent = true). Name, code, email,
  role and manager are kept in line with the call; a person created here can later sign in through the gateway token and is the same person.
- **Manager**: `managerEmployeeId` is resolved by `jurisone_employee_id` among the people of the same call or already stored in the business, so **the order
  of the list does not matter**; absent or null = no manager; a person is never their own manager (that means none). A manager who is unknown, or a
  reporting line that would go round in a circle, skips the person who has the problem (`unknown_manager`, `manager_cycle`); a new person whose manager is
  skipped is skipped too.
- **Active**: `isActive: false` deactivates exactly like `PUT /api/v1/employees/{employeeId}/status` (the open workday is closed, `workday.closed`; every
  session ends) and marks the person as deactivated by the sync (`users.is_deactivated_by_roster_sync`). `isActive: true` reactivates **only a person the
  sync deactivated**: somebody the owner deactivated (any manual status change clears the mark), or whose login access the owner switched off, stays as
  they are. A new person sent with `isActive: false` is **not created** (counted in `employees.unchanged`, not in `skipped`). The owner (`ADMIN`) is never deactivated by the sync (`owner_cannot_be_deactivated`).
- **`shiftCode`**: assigns that shift from today (business zone) exactly like `PUT /api/v1/employees/{employeeId}/shift`: history is kept, the same shift
  again changes nothing; absent or null leaves the assignment. Unknown code `unknown_shift_code`, deactivated shift `shift_inactive`.
- **`schedule`**: up to 62 entries per employee (`schedule_too_long` skips the person), one per date (`duplicate_schedule_date`, `invalid_schedule_date`),
  `shiftCode` null = a week off; idempotent upsert per date (the same records as `PUT /api/v1/employees/{employeeId}/schedule-entries`; a reason the owner
  wrote on an entry is kept). A date that already has a workday is not changed and a date more than 365 days ahead is refused: both are listed in
  `skippedScheduleEntries` (the person is still applied). Dates not listed are left as they are.
- **Per-person problems never fail the batch**: the person is skipped, listed in `skipped` with a reason code, and everything else is applied. Reason
  codes: `invalid_email`, `invalid_name`, `invalid_employee_code`, `invalid_role`, `invalid_manager_id`, `invalid_schedule_date`, `duplicate_schedule_date`,
  `schedule_too_long`, `duplicate_employee_in_request`, `duplicate_email_in_request`, `duplicate_employee_code_in_request`, `email_of_another_business`,
  `email_of_super_admin`, `email_linked_to_another_employee`, `email_in_use`, `employee_of_another_business`, `employee_code_in_use`,
  `unknown_shift_code`, `shift_inactive`, `unknown_manager`, `manager_cycle`, `owner_cannot_be_deactivated`.

200 `{ ok: true, data }` with
```json
{ "shifts": { "created": 0, "updated": 0, "unchanged": 0 },
  "employees": { "created": 0, "updated": 0, "deactivated": 0, "reactivated": 0, "unchanged": 0 },
  "scheduleEntries": { "set": 0, "unchanged": 0, "skipped": 0 },
  "skipped": [ { "employeeId": "<JurisOne id>", "reason": "unknown_manager" } ],
  "skippedShifts": [ { "code": "US", "reason": "shift_name_in_use" } ],
  "skippedScheduleEntries": [ { "employeeId": "<JurisOne id>", "date": "2026-11-03", "reason": "schedule_date_has_workday" } ] }
```
A person is counted once: `created`, else `deactivated`, else `reactivated`, else `updated` (their details, manager or shift assignment changed), else
`unchanged`; skipped people are in no count. Errors: 400 `VALIDATION_FAILED` with `errors` (`employees[i].employeeId` missing or not a UUID, a bad shift
`shifts[i].code` / `name` / `startTime` / `endTime` / grace, a code listed twice, more than 200 shifts, a malformed body; nothing is applied), 401, 403
`BUSINESS_SUSPENDED` (audited as `integration.user_refused`, reason `business_suspended`), 413 `PAYLOAD_TOO_LARGE` (more than 5000 employees, or a
body above 16 MB).

**Audit** (source `INTEGRATION`, no actor, the `X-Request-Id` of the call stored with every entry): one `integration.roster_synced` per call (entity
`business`; `after` = counts received / created / changed / skipped, skipped people counted per reason; no names or emails), plus the usual entry of each
change: `integration.business_provisioned` / `integration.business_updated`, `integration.user_provisioned` / `integration.user_linked` /
`integration.user_updated`, `shift.created` / `shift.updated`, `employee.deactivated` / `employee.reactivated` (reason `jurisone_roster_sync`) with
`workday.closed`, `employee.shift_changed`, `schedule.entry_set`. A repeat that changes nothing writes only `integration.roster_synced`.

**First user token and `shiftCode`**: a user token may carry an optional `shiftCode` (a `code` from the roster's shifts). For a user **just created by
that token** (never an existing one) the shift is assigned from today (`employee.shift_changed`, source `INTEGRATION`); an unknown, blank, deactivated or
non-text code is ignored.

## Audit log

A complete, append-only record of every movement (owner decision, `MIGRATION_PLAN.md` "Audit policy"). Table `audit_logs` (V1, extended by V3).

**An entry**: `createdAt` (UTC, the server's real time), `businessId` (null = a platform event: the super admin's lists and exports, sign-in attempts
for unknown emails, refused tokens), `actor` (who did it; `actorRole` = their role at that moment), `subject` (whom it is about), `action`, `category`
(`auth`, `people`, `business`, `time`, `security`, `access`, `integration`, `audit`), `outcome` (`SUCCESS`; `DENIED` = refused for a rule, a role or a
security reason; `FAILED` = allowed but went wrong), `entityType` / `entityId`, `before` / `after` (JSON), `reason`, and the request: `source`
(`STANDALONE` cookie session or standalone sign-in, `INTEGRATION` JurisOne's Bearer token, `SYSTEM` no HTTP request), `ipAddress` (the connection's address, or,
behind a trusted proxy, the client it forwarded in `X-Forwarded-For`; the same as the rate limits, see `POST /api/v1/auth/otp-requests`), `userAgent` (at most 400 characters), `requestId`,
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
| `employee.shift_changed` | people | SUCCESS | the owner gave an employee a shift from a date (before / after: shift and date) |
| `schedule.entry_set` | people | SUCCESS | the owner set one date of an employee's roster to a shift or a week off (before / after; one entry per changed date) |
| `schedule.entry_removed` | people | SUCCESS | the owner removed a roster entry (before) |
| `shift.created` / `shift.updated` / `shift.status_changed` | business | SUCCESS | a shift was created / changed (before and after) / deactivated or reactivated |
| `access.employee_list_viewed` | access | SUCCESS | the owner listed the people (`after` = filters, `rowCount`, `total`) |
| `access.employee_viewed` | access | SUCCESS | the owner opened another person's record |
| `access.business_list_viewed` | access | SUCCESS | the super admin listed the businesses (platform log) |
| `access.business_viewed` | access | SUCCESS | the super admin opened a business (that business's log, subject = owner) |
| `audit.exported` | audit | SUCCESS | an audit log CSV export (`after` = the filters and `rowCount`) |
| `business.created` / `business.updated` | business | SUCCESS | a business was created / its settings changed (before and after) |
| `business.suspended` / `business.reactivated` | business | SUCCESS | the super admin suspended / reactivated a business (with the reason) |
| `employee.created` / `employee.updated` | people | SUCCESS | a person was added / changed (whole record before and after, role and manager included) |
| `employee.deactivated` / `employee.reactivated` | people | SUCCESS | with the reason; deactivation lists `endedSessions` and `closedWorkdayId` |
| `employee.login_disabled` / `employee.login_enabled` | people | SUCCESS | the owner switched the person's sign-in off / on, with the reason; switching off lists `endedSessions` and `closedWorkdayId` |
| `employee.change_refused` | people | DENIED | a 422 business rule refused a change (`MANAGER_CYCLE`, `LAST_ACTIVE_OWNER`, `CANNOT_DEACTIVATE_SELF`, `ROLE_NOT_ASSIGNABLE`, `MANAGER_*`, ...) |
| `time.workday_started`, `time.workday_reopened`, `time.break_started`, `time.work_resumed`, `time.workday_ended` | time | SUCCESS | an applied time event |
| `time.event_failed` | time | FAILED | a time event refused with 500 `TIME_RECORD_INCONSISTENT` |
| `workday.closed` | time | SUCCESS | somebody else closed a workday because of a deactivation, a switched-off login (reason `login_disabled`) or a suspension of the business (reason `business_suspended`) |
| `time.session_adjusted` | time | SUCCESS | a manager or the owner corrected a work or break interval (interval and workday totals before / after, the reason) |
| `time.workday_closed` | time | SUCCESS | a manager or the owner ended somebody's open workday (`PUT /api/v1/workdays/{workdayId}/status`), with the closed intervals |
| `time.correction_refused` | time | DENIED | a 422 business rule refused a correction (`TIME_INTERVAL_OVERLAP`, `WORKDAY_OVERLAP`, `CORRECTION_OUT_OF_RANGE`, `WORKDAY_MUST_START_WITH_WORK`, `INTERVAL_STILL_RUNNING`, ...) |
| `access.team_member_viewed` | access | SUCCESS | a manager or the owner opened one person's live state |
| `access.team_member_workdays_viewed` | access | SUCCESS | a manager or the owner opened one person's history (`after = { range, from, to }`) |
| `access.workday_viewed` | access | SUCCESS | a manager or the owner opened somebody else's workday (`after.date`) |
| `reports.viewed` | access | SUCCESS | a report was read (`after = { report, filters, rowCount }`), one per request |
| `reports.exported` | access | SUCCESS | the workday report was downloaded as CSV (`after` adds `timeZone`, `fileName`) |
| `integration.business_provisioned` | integration | SUCCESS | a JurisOne token created a business (a token never links an existing one) |
| `integration.business_linked` / `integration.business_unlinked` | integration | SUCCESS | the super admin linked / unlinked a business to JurisOne (`PUT` / `DELETE /api/v1/businesses/{businessId}/jurisone-link`; `after` / `before.jurisoneBusinessId`) |
| `integration.business_updated` | integration | SUCCESS | the token renamed a linked business |
| `integration.user_provisioned` / `integration.user_linked` / `integration.user_updated` | integration | SUCCESS | a token or the roster sync created / linked / changed a user |
| `integration.roster_synced` | integration | SUCCESS | one per roster sync call (`PUT /api/v1/integration/employees`), also when nothing changed: counts only (`receivedEmployees`, `shifts`, `employees`, `scheduleEntries`, `skippedEmployeesByReason`); source INTEGRATION, no actor |
| `integration.token_rejected` | integration | DENIED | a refused JurisOne token: reason code only (`bad_signature`, `expired`, `wrong_issuer`, `wrong_audience`, `malformed_token`, `missing_issue_time`, `lifetime_too_long`, `replayed_token`, `missing_scope`, `wrong_scope`, `not_a_service_token`, `service_token_not_accepted`, `missing_claim: <name>`, ...), never the token; once per reason, IP, window |
| `integration.user_refused` | integration | DENIED | a valid token whose person may not use JurisTrack (`user_deactivated`, `login_disabled`, `email_of_another_business`, `conflict`), or a roster sync of a suspended business (`business_suspended`); once per reason, user, window |

**Volume decisions**: one entry per write; reads of somebody else's data one entry per request (the Team tab's live board, which refreshes every few
seconds, is not recorded; opening one person, their history or workday, and every report and export is); never audited: reading one's own data
(`GET /api/v1/me`, `/api/v1/me/time` (polled), `/api/v1/workdays/mine`, one's own workday, one's own employee record, `GET /api/v1/business`), a successful session
refresh, a 401 without a session or with an access token that merely ran out, validation errors and conflicts (400, 409), a 404 for a route or a record
of one's own scope that does not exist (but a 404 for somebody else's person or workday is `security.out_of_scope_access`, once per window), viewing the audit log
(only exports). High-volume security events (rate limits, refused refreshes, bad cookies, refused tokens, suspended-business requests, cross-site
writes) are written at most once per key in each 15-minute window (in memory per API instance; `after.aggregationWindowMinutes`). Refusals and failures
are written in a transaction of their own, so they stay although the request fails. Reads of other people's data write their entry **in the read's own
transaction** (one database connection per request; the data is never served without its entry). Entries that nobody signed in caused (sign-in attempts,
refused tokens and cookies) are capped per IP address and window (`juristrack.audit.unauthenticated-entries-per-ip`, default 500): the log is append-only.

### GET /api/v1/audit-logs
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

### GET /api/v1/audit-logs/{auditLogId}
ADMIN, SUPER_ADMIN. `data` = the row plus `before`, `after` (JSON or null), `userAgent`, `httpMethod`, `requestPath`. 404 unknown or of another business
(the owner), 400 malformed id. Not audited.

## Database migrations

Flyway, `backend/src/main/resources/db/migration`, each with its rollback in `db/rollback`: `V1__baseline` (schema, row-level security),
`V2__business_suspension_and_email_check`, `V3__complete_append_only_audit`, `V11__workdays_business_date_index` (reports), `V12__login_access`, `V20__shifts`, `V21__roster_sync` (`shifts.code`, `users.is_deactivated_by_roster_sync`). Applied migrations are never
edited; the next one is V22 (Flyway refuses a new version lower than the highest applied one).
