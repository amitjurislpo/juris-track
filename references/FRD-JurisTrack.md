# JurisTrack: Functional Requirements Document (FRD)

| Version | Date | What changed | Requested by |
|---|---|---|---|
| 1.0 | 2026-10-23 | Initial version | Owner |
| 1.2 | 2026-10-24 | Login access: the owner can switch an employee's sign-in off and on (`PUT /api/employees/{employeeId}/login-access`), separate from Deactivate; FR-AUTH-15, FR-EMP-10 to FR-EMP-14, FR-INT-8 | Owner |

This is the document version, not the app version. Companion documents (same folder): [FLOW-JurisTrack.md](FLOW-JurisTrack.md) (the flow diagrams of every flow named here) and [FLOW-JurisTrack-JurisOne-Connection.md](FLOW-JurisTrack-JurisOne-Connection.md) (how JurisTrack and JurisOne talk to each other).
Sources: `API.md` (the endpoint contract), `INTEGRATION.md`, `MIGRATION_PLAN.md`, `timetrack.txt` (the original brief), the backend code and the `juris-track-frontend` screens. Where the sources disagree this document says so in section 17 "Open points".

Requirement ids are `FR-<module>-<n>`. Every requirement is written so that a tester can check it with one call or one screen. Error codes (`CORRECTION_OUT_OF_RANGE`, ...) are the stable `code` of the API's `application/problem+json` answer; action names (`time.session_adjusted`, ...) are the real values stored in the audit log.

---

## 1. Purpose and scope

**Purpose.** JurisTrack records how long each employee really works during the day and how long they take breaks, and adds that up to productive hours. An employee starts the workday, takes breaks, resumes and ends the day. A manager sees, live, who of the people reporting to them is working or on a break, and their history and reports. The owner of the company manages the people, corrects forgotten or wrong times, reads reports and the complete audit log.

**What the product does (in scope)**
- Businesses (companies) with an owner, managers and employees; several businesses share one installation and never see each other.
- Own time tracking for every user of a business: workday, work sessions, break sessions, personal dashboard, My time, My history.
- The Team board (live), one person's page and history, time corrections, ending a forgotten workday.
- Reports with totals, daily series, grouped summaries and CSV export.
- A complete, append-only audit log with filters, detail and CSV export.
- Display time zone, English and Hindi.
- Two ways of use with the same rules: **standalone** (own sign-in by emailed code) and **embedded in JurisOne** (JurisOne signs the user in and calls the API through its gateway).

**Out of scope** (section 16): passwords, teams as objects, payroll, leave, shifts, attendance policy, notifications, mobile apps, roster sync (not built yet), self-service sign-up of companies.

**Who reads this.** The owner (what the product must do), a new developer (rules and edge cases), a tester (numbered, checkable requirements).

---

## 2. Roles, permissions and who sees whom

### 2.1 The four roles

| Role | Who | Belongs to a business | Main purpose |
|---|---|---|---|
| `SUPER_ADMIN` | Platform user of the product owner | No | Creates and suspends businesses, creates each business's owner, links a business to JurisOne, reads the platform audit log |
| `ADMIN` (shown as **Owner**) | The company's owner | Yes | Adds people, assigns managers, sees everybody of the business, corrects time, reports, audit log, settings |
| `MANAGER` | A person who has reports | Yes | Sees the live board, history and reports of the people under them and corrects their time |
| `EMPLOYEE` | Everybody else | Yes | Tracks own time and sees own history |

There is no HR, Finance or other functional role. There are no permission tables: the four roles are fixed. The API enforces every rule; hiding a button in the screen is only a convenience.

### 2.2 Permission matrix

Y = allowed, N = refused with 403 `FORBIDDEN`, own = only for oneself. A signed-out caller always gets 401.

| Function | SUPER_ADMIN | ADMIN | MANAGER | EMPLOYEE |
|---|---|---|---|---|
| Sign in with emailed code | Y | Y | Y | Y |
| Own profile (`GET /api/me`) | Y | Y | Y | Y |
| List, create, view, edit, suspend businesses; link or unlink JurisOne | Y | N | N | N |
| View and change own business settings | N | Y | N | N |
| List, add, edit, deactivate, reactivate employees | N | Y | N | N |
| Switch an employee's login off or on | N | Y (not own login) | N | N |
| Own time: start, break, resume, end; own dashboard, My time, My history, own workday | N (403) | Y | Y | Y |
| Team board, one person, their history and workdays | N | Y (everybody) | Y (own reporting line) | N |
| Correct a work or break interval, end a forgotten workday | N | Y (everybody except self) | Y (own reporting line except self) | N |
| Reports and CSV | N | Y | Y | N |
| Audit log: list, detail, CSV | Y (every entry) | Y (own business) | N | N |

### 2.3 Who sees whom (the reporting line)

There are **no team objects**. The unit is the reporting line: every user has at most one manager (`manager_id`). It is read when the request runs, not taken from old workdays.

- **Direct report**: a user whose manager is the caller.
- **Indirect report**: a direct report of a direct report (one level further down). The depth is a parameter of the code (1 = direct, 2 = direct plus one level), today at most 2.
- **MANAGER**: board and reports show direct reports by default; the "Include indirect reports" switch (`scope=indirect`) adds the next level. Opening one person, their history, one of their workdays and correcting their time works for direct and indirect reports, whatever the board's switch shows. The manager never sees people above or beside them.
- **ADMIN (owner)**: board and reports default to `scope=all` (everybody in the business, the owner included in reports). `scope=direct` or `indirect` shows the owner's own reporting line. One person, history, workdays and corrections work for everybody of the business.
- **Nobody is in their own scope.** Their own record is the personal section; `GET /api/team/members/{ownId}` answers 404.
- **Out of scope = 404.** A person above or beside the caller, in another business, or an unknown id all answer 404 `NOT_FOUND`, never 403, so a caller cannot learn that a record exists. The first such probe per user and window is audited as `security.out_of_scope_access`.
- Deactivated people stay in the scope for reading and correcting their past days. The live board leaves them out.

---

## 3. Module AUTH: sign-in by emailed code and sessions

**Screens.** Sign-in page (email field, then six code boxes, "keep me signed in" tick), "Your company's access is paused" screen (suspended business), sign-out in the shell. Embedded in JurisOne the host owns sign-in and the shell.

**Functional requirements**
- FR-AUTH-1 `POST /api/auth/otp-requests` with an email always answers 200 with the same text ("If that account exists and is active, a 6-digit code is on its way to the email."), so a caller cannot learn whether an email exists. A missing or invalid email is 400.
- FR-AUTH-2 A code is emailed only to a known, active user of a business that is not suspended (or to the super admin). For an unknown or deactivated account or a suspended business no code is sent.
- FR-AUTH-3 A code has 6 digits, works once, for 10 minutes and for 5 wrong tries (the wrong try that uses up the 5th burns the code). Only the hash of the code is stored. In `dev` the code is always `000000`; in sqe, uat and prd it is random and the API refuses to start with a fixed code.
- FR-AUTH-4 `POST /api/auth/sessions` with email, code and optional `remember` creates a session and answers 201 with the cookies `jt_at` (access token, 5 minutes) and `jt_rt` (refresh token, rotates on every use). A wrong, expired, used or burnt code, an unknown or deactivated account and a suspended business all answer 401 with the same message.
- FR-AUTH-5 With "keep me signed in" the session lasts `session-ttl-days` (30 in dev, 14 in prd); without it 12 hours and it ends with the browser.
- FR-AUTH-6 `POST /api/auth/session-refreshes` renews the cookies. A refresh token used again within 30 seconds (two requests of one browser at once) gets a new access token only; used again later it ends the whole session (stolen copy). A session that ended, expired, belongs to a deactivated user, or whose token was used twice answers 401 and clears the cookies.
- FR-AUTH-7 The web app answers a 401 on any call with one silent session refresh and one retry; if that fails it goes to the sign-in page.
- FR-AUTH-8 `DELETE /api/auth/sessions/current` always answers 200 and ends this device's session.
- FR-AUTH-9 Deactivating a user ends all their sessions at once; their next request is 401.
- FR-AUTH-10 Every cookie-authenticated `POST`, `PUT`, `PATCH` or `DELETE` must carry an `Origin` equal to the web app's URL, else 403 `CROSS_SITE_BLOCKED`. Bearer (JurisOne) requests are exempt. Request bodies above 64 KB answer 413 `PAYLOAD_TOO_LARGE`.
- FR-AUTH-11 Every answer carries `X-Request-Id`; a caller may send its own (1 to 64 letters, digits, `.`, `_`, `:`, `-`), else a new UUID is made. It is stored in every audit entry of the request.
- FR-AUTH-12 After sign-in the user lands on the home of their role: `/platform` (super admin), `/admin` (owner), `/team` (manager), `/dashboard` (employee).

**Rate limits (per 15-minute window, in memory of one API instance)**

| Limit | Value | Reason code in the audit entry |
|---|---|---|
| Code requests per IP address | 10 | `code_requests_per_ip` |
| Code requests per email | 5 | `code_requests_per_email` |
| Failed sign-ins per IP address | 20 | `failed_sign_ins_per_ip` |
| Failed sign-ins per account | 10 (from any address) | `failed_sign_ins_per_email` |

- FR-AUTH-15 A person whose login the owner switched off (FR-EMP-10) cannot sign in: a code request and a code check answer exactly as for an unknown account (same 200 text, same 401 message, no code sent), the audit reason is `login_disabled`, their sessions end at once and a refresh is refused (401). Switching the login on again restores sign-in only.
- FR-AUTH-13 Above a limit the API answers 429 `RATE_LIMITED`; only failed sign-ins count, successful ones do not.
- FR-AUTH-14 The IP address is the connection's. Only when the request comes from a **trusted proxy** (`juristrack.security.trusted-proxies`, default loopback only; in sqe/uat/prd set to the web server or gateway) is the client taken from `X-Forwarded-For` (the right-most hop that is not a proxy). A caller therefore cannot choose its own address by writing the header.

**Business rules.** Email addresses are unique across all businesses (compared lower-cased), so the business is found from the email. A suspended business's users get no codes and cannot sign in; existing sessions are not ended, they get 403 `BUSINESS_SUSPENDED` and work again after reactivation. Sign-out still works while suspended.

**Data kept.** `login_otps` (code hash, expiry, attempts, used time, IP), `sessions` (hash of the token, expiry, last seen, IP, user agent, revoked time and reason), `refresh_tokens` (hash, session, used time).

**Audit entries.** `auth.sign_in_code_requested` (SUCCESS when sent, DENIED with reason `unknown_email`, `inactive` or `business_suspended`), `auth.signed_in`, `auth.sign_in_failed` (reason `wrong_code`, `expired_or_used_code`, `code_burnt`, `unknown_email`, `inactive`, `business_suspended`), `auth.sign_in_code_burnt`, `auth.rate_limited`, `auth.signed_out`, `auth.session_revoked_refresh_reuse`, `auth.session_refresh_failed` (reason `unknown`, `revoked`, `expired`, `inactive`), `security.invalid_session_cookie`, `security.cross_site_request_blocked`, `security.suspended_business_request`. A successful refresh is not audited.

**Errors and edge cases.** Two tabs refreshing at once are covered by the 30-second grace. A user deactivated while signed in is signed out on the next request. A suspended business shows the "access is paused" screen.

---

## 4. Module BIZ: businesses and owners (platform)

**Screens.** Businesses list (search by business name, owner name or owner email; status filter all/active/suspended; sort by name, created, employee count, status; paging), "New business" dialog, one business page (owner, head counts, settings, edit, suspend/reactivate with confirmation and optional reason, JurisOne link state), platform Audit Log.

**Functional requirements**
- FR-BIZ-1 Only the super admin may call `/api/businesses/**`; everybody else signed in gets 403.
- FR-BIZ-2 `POST /api/businesses` with `name` (1 to 80), optional IANA `timeZone` (default `Asia/Kolkata`) and `owner` (first name, last name, valid email up to 200, optional employee code) creates the business and its owner (role ADMIN, active, no manager) in one transaction and answers 201 with `{ id, ownerId }`. Defaults: stale workday hours 14, display time zones `Asia/Kolkata` and `America/New_York`.
- FR-BIZ-3 An email already used by any user of any business or by the super admin answers 409 `EMAIL_IN_USE` (compared lower-cased). Invalid fields answer 400 with the field names `name`, `timeZone`, `owner.firstName`, `owner.lastName`, `owner.email`, `owner.employeeCode`.
- FR-BIZ-4 The owner signs in with an emailed code (no password, no invitation link): the super admin tells the owner to open the sign-in page and request a code for the owner's email.
- FR-BIZ-5 `PATCH /api/businesses/{businessId}` changes name, time zone, stale workday hours (1 to 48) and display time zones (at most 8, duplicates dropped, none may be `null`). Time zones are checked against the IANA database and stored canonical. A new time zone applies to workdays started from then on.
- FR-BIZ-6 `PUT /api/businesses/{businessId}/status` with `isSuspended` and optional `reason` (up to 500) suspends or reactivates. While suspended, users get no codes and every request answers 403 `BUSINESS_SUSPENDED`; sessions stay.
- FR-BIZ-7 **Suspending closes every open workday of the business** at that moment: the open work or break interval ends, the day becomes `DAY_ENDED` with recomputed totals, one `workday.closed` entry per person (reason `business_suspended`, actor the super admin). Reactivating reopens nothing.
- FR-BIZ-8 `PUT /api/businesses/{businessId}/jurisone-link` with `jurisoneBusinessId` links a standalone business to its JurisOne business; the same link again changes nothing and writes no entry. 409 `BUSINESS_ALREADY_LINKED` (linked to another JurisOne business; remove the link first), 409 `JURISONE_BUSINESS_IN_USE` (another business has that JurisOne id).
- FR-BIZ-9 `DELETE /api/businesses/{businessId}/jurisone-link` removes the link; the business stays standalone and its people keep their accounts. A business that is not linked is left as it is.
- FR-BIZ-10 A JurisOne token never adopts an existing business by email; only FR-BIZ-8 links one (see section 15).
- FR-BIZ-11 A business and its owner can never be deleted (people are deactivated, businesses suspended); they appear in the audit log with restricting foreign keys.

**Data kept.** `businesses`: name, `time_zone`, `stale_workday_hours`, `display_time_zones`, `is_suspended`, `jurisone_business_id`, created and updated time.

**Audit entries.** `business.created` and `employee.created` (the owner), `business.updated` (only when something changed), `business.suspended`, `business.reactivated` (with reason), `workday.closed` (one per closed day), `integration.business_linked`, `integration.business_unlinked`, `access.business_list_viewed` (platform log), `access.business_viewed` (in that business's log, subject the owner, so the owner can see that the platform looked).

**Edge cases.** A JurisOne-created business may have no ADMIN (owner fields null in the list). The owner is the earliest-created ADMIN.

---

## 5. Module SET: business settings (owner)

**Screen.** Settings: business name, time zone, stale workday hours, display time zones.

- FR-SET-1 `GET /api/business` and `PATCH /api/business` are for the ADMIN of the business only; rules and validation as FR-BIZ-5. Success message "Settings saved.".
- FR-SET-2 The business time zone is always offered as a display zone, even when not in the list (the client adds it).
- FR-SET-3 Changing the stale hours affects the "Not ended" flag of open days at once (the flag is computed when read).
- Audit: `business.updated` with before and after. Reading settings is not audited.

---

## 6. Module EMP: employees (owner)

**Screens.** Employees list (search by name, "first last", email, code; status active/inactive/all, default active; role and manager filters; sortable; paging), New employee, Employee page (edit fields, deactivate/reactivate, "Time and history" link).

**Functional requirements**
- FR-EMP-1 Only the ADMIN calls `/api/employees/**`. The list covers every user of the owner's business, the owner included, never the super admin. A user of another business answers 404.
- FR-EMP-2 `POST /api/employees`: first and last name (1 to 60), email (valid, up to 200), optional employee code (letters, digits, `-`, `_`, up to 32), role `EMPLOYEE` (default) or `MANAGER`, optional `managerId`. The employee is active at once, signs in with an emailed code, and the answer is 201 with `{ id }`.
- FR-EMP-3 409 `EMAIL_IN_USE` (any business) and 409 `EMPLOYEE_CODE_IN_USE` (this business).
- FR-EMP-4 Role `ADMIN` or `SUPER_ADMIN` cannot be given: 422 `ROLE_NOT_ASSIGNABLE` (also when changing an ADMIN's role).
- FR-EMP-5 **Manager rules** whenever `managerId` is set to a new value: 422 `MANAGER_NOT_FOUND` (unknown or of another business), `MANAGER_INACTIVE`, `MANAGER_ROLE_REQUIRED` (the manager must be MANAGER or ADMIN), `MANAGER_IS_SELF`, `MANAGER_CYCLE` (the chosen manager reports, directly or further down, to this employee).
- FR-EMP-6 `PATCH /api/employees/{employeeId}` changes only the fields sent; `null` clears the code or the manager; `null` for name, email or role is 400. A MANAGER who has active reports cannot become an EMPLOYEE: 422 `MANAGER_HAS_REPORTS`. A role change applies on the user's next request.
- FR-EMP-7 `PUT /api/employees/{employeeId}/status` with `isActive` and optional `reason` deactivates or reactivates; it is idempotent (setting the state already held changes nothing and writes nothing).
- FR-EMP-8 Deactivating (a) ends every session of the person, (b) stops new sign-in codes, (c) closes the person's open workday at that moment (work or break interval ends, day completed, totals recomputed), (d) leaves their reports with them as manager. Refusals: 422 `CANNOT_DEACTIVATE_SELF`, 422 `LAST_ACTIVE_OWNER` (the business must keep one active ADMIN).
- FR-EMP-9 A refused change (any 422 above) is audited as `employee.change_refused` (DENIED, the code as reason) although nothing changes.
- FR-EMP-10 **Login access** is separate from Deactivate. `PUT /api/employees/{employeeId}/login-access` with `isEnabled` and optional `reason` switches a person's sign-in off or on. A person with login off stays active, in the list, on the board and in the reports, and their history is kept; they only cannot sign in. It is idempotent (the state already held changes nothing and writes nothing). `isLoginEnabled` is part of every employee row and the detail (default true).
- FR-EMP-11 Only the ADMIN (owner) of the same business calls it: MANAGER and EMPLOYEE get 403, a person of another business, the super admin or an unknown id 404.
- FR-EMP-12 Switching off (a) ends every session of the person, so the next request is 401 and a refresh is refused, (b) closes the person's open workday at that moment, as FR-EMP-8 does, with the reason `login_disabled` (no day keeps running for somebody who cannot stop it, also across midnight for a night shift), (c) refuses sign-in as FR-AUTH-15 says. Switching on restores sign-in only; nothing is reopened.
- FR-EMP-13 The owner cannot switch off their own login: 422 `CANNOT_DISABLE_OWN_LOGIN` (audited `employee.change_refused`). The web app hides the button on the owner's own page.
- FR-EMP-14 The Employees list shows a "Login off" chip next to the status, and the employee page has a "Login access" card (state, "Disable login" / "Enable login", a confirmation with an optional reason).

**Data kept.** `users`: employee code, names, unique email, role, `is_active`, `is_login_enabled`, last login, `manager_id` (same business), `jurisone_employee_id`. Database checks: the super admin is the only user without business; nobody is their own manager.

**Audit entries.** `employee.created`, `employee.updated` (the whole record before and after; only when something changed), `employee.deactivated` (after lists `endedSessions` and `closedWorkdayId`), `employee.reactivated`, `employee.login_disabled` and `employee.login_enabled` (with the reason; disabling lists `endedSessions` and `closedWorkdayId`), `employee.change_refused`, `workday.closed`, `access.employee_list_viewed` (filters, `rowCount`, `total`), `access.employee_viewed` (not when the owner opens their own record).

---

## 7. Module TIME: own time tracking (all users of a business)

**Screens.** **Dashboard** (greeting, today's card with the same time buttons, this week's productive and break time per day as a chart, the last five workdays), **My time** (state chip, big productive timer, buttons *Start work*, *Take a break*, *Back to work*, *End workday* with confirmation, *Start again*; today's totals and timeline; "Not ended" warning; summary of a day ended after midnight; "offline" notice with *Check again*).

### 7.1 Workday states

| State shown | Meaning | Stored workday status |
|---|---|---|
| Not started | No open workday and none ended today | no row |
| Working | Open day, a work session is running | `WORKING` |
| On break | Open day, a break session is running | `ON_BREAK` |
| Day ended | The day was ended (by the person, a manager/owner, a deactivation or a suspension) | `COMPLETED` (shown as `DAY_ENDED` in the API) |
| Not ended | Flag on an open day whose last uninterrupted stretch is longer than the business's stale hours (default 14) | still `WORKING` or `ON_BREAK` |

**Time buttons per state:** Not started: Start work. Working: Take a break, End workday. On break: Back to work (End workday is refused until the break is ended). Day ended: Start again.

### 7.2 Functional requirements
- FR-TIME-1 The employee is always the signed-in user; an id in the body is ignored. SUPER_ADMIN gets 403 on every own-time endpoint. A suspended business gets 403 `BUSINESS_SUSPENDED`; a deactivated user is signed out.
- FR-TIME-2 `GET /api/me/time` answers `serverTime`, `today` (date in the business time zone), business time zone, stale hours, display time zones, `status` and the `workday` snapshot (or null). The `workday` is the open workday of any date, else today's ended workday, else null. It is polled and never audited.
- FR-TIME-3 `POST /api/me/time-events` with `type` = `START`, `BREAK`, `RESUME` or `END` (any case). A missing or unknown type is 400 on field `type`.
- FR-TIME-4 State machine: Not started + START starts a workday with its first work session; Working + BREAK ends the work session and starts a break; On break + RESUME ends the break and starts a work session; Working + END ends the work session and completes the day; Day ended + START reopens the day (7.3).
- FR-TIME-5 An event that leads where the person already is is a **repeat**: START while working or on a break, BREAK while on a break, RESUME while working, END after the day ended. It answers 200 with `alreadyApplied: true`, creates nothing and writes no audit entry. A real change answers 201 with a message ("Workday started. Have a productive day.", "Break started. Your productive timer is paused.", "Welcome back. Your productive timer is running.", "Workday ended. Your summary is ready.").
- FR-TIME-6 Refused events answer 409: `WORKDAY_NOT_STARTED` (BREAK or END before the day started, also BREAK after the day ended), `NOT_ON_BREAK` (RESUME without a break), `BREAK_IN_PROGRESS` (END while on a break: "Select Back to Work before ending your workday").
- FR-TIME-7 Requests of one person are serialized (row lock on the user), so parallel requests never create a second workday or a second open interval; exactly one of them answers 201. A collision that no concurrent request explains answers 500 `TIME_RECORD_INCONSISTENT` and writes `time.event_failed` (FAILED); an administrator must correct the day.
- FR-TIME-8 Time comes from stored timestamps, never from a counter in the browser. Events are stamped to the millisecond and never before the latest instant already recorded for the person, so a clock that steps back cannot put an interval before the previous one.
- FR-TIME-9 The screen keeps a timer running from `closedProductiveSeconds + (now - activeWorkStartedAt)` on the **server's** clock (offset measured on every answer), ticks once a second, stops while the tab is hidden, re-reads every 30 seconds, on focus and when the network returns. A refresh, a sleeping tab or a wrong browser clock never changes a figure.
- FR-TIME-10 A time button is disabled while its request runs, so a double click sends one event.

### 7.3 Business rules
- **Productive vs break time.** Productive time = the sum of the work sessions; break time = the sum of the break sessions; a running interval counts up to now. Productive time does not grow during a break. Span = start of the day to its end (or now).
- **Example.** Work 09:00-11:15, break 11:15-11:30, work 11:30-13:00, break 13:00-13:45, work 13:45-18:00: span 9 h, breaks 1 h, productive 8 h, 2 breaks.
- **One workday per person and date.** The date is the calendar date **in the business time zone when work started**. A workday may run past midnight (night shift) and keeps its start date; the open workday is the person's current state whatever its date. After a night shift ended past midnight, today has no workday and the state is Not started again.
- **Reopening after End Day.** START after the day ended on the **same date** reopens that workday with a new work session. The gap between the end and the new start counts as neither productive nor break time (it stays inside the span). Reopening is audited as `time.workday_reopened`. The day's stored totals are emptied while it is open and recomputed when it ends again.
- **"Not ended".** `isStale` is true when the day is still open and its last uninterrupted stretch (from the start, or from the last restart after a gap) exceeds the stale hours. It is computed when read; nothing is scheduled. A day reopened late in the evening is not flagged because of the morning.
- **No automatic closing.** No background job ends a workday. A day ends only by the person, by a manager/owner (FR-COR-8), by a deactivation (FR-EMP-8) or by a suspension (FR-BIZ-7).
- **Break before End.** Ending the day while on a break is refused, so that the break's end time is real.
- **Manager snapshot.** The workday keeps the person's manager at the time it started (`manager_id`); reports can group by this "manager of the day".
- **Totals.** Stored totals (productive, break, break count) exist only for ended days and are recomputed from the intervals, never incremented; an open day is computed live.

**Data kept.** `workdays` (employee, manager snapshot, `work_date`, time zone it started in, start, end, status, totals), `work_sessions` and `break_sessions` (workday, start, end). Database rules: one open workday per person, one open work session and one open break per day, end not before start, an open/closed state that matches its key.

**Audit entries.** `time.workday_started`, `time.workday_reopened`, `time.break_started`, `time.work_resumed`, `time.workday_ended` (before `{status}`, after `{status, at}`; actor and subject the person), `time.event_failed`. Repeats and `GET /api/me/time` write nothing.

**Edge cases.** *DST*: durations come from instants, so a 23-hour or 25-hour day is counted correctly; only dates and the correction window follow the zone. *Business time zone changed*: affects workdays started later. *Business suspended*: open days are closed (FR-BIZ-7) and own-time calls answer 403. *Deactivated employee*: the open day was closed; a request in flight answers 403 "This account is deactivated.". *Offline*: the screen shows "offline" and keeps the last state; nothing is recorded locally.

---

## 8. Module HIST: My history and the workday detail

**Screen.** My history: range (Today, Yesterday, This week, This month (default), Custom with from/to), totals and average per day, productive and break time per day (chart), sortable paged table; a row opens the workday with its intervals.

- FR-HIST-1 `GET /api/workdays/mine` returns the person's workdays dated in the range: `range` = `today`, `yesterday`, `week` (Monday to today), `month` (the 1st to today), `custom` with `from` and `to` (`YYYY-MM-DD`). Missing `from` = this week's Monday, missing `to` = today, reversed ends are swapped, at most 366 days (an earlier `from` is moved up). Unknown range or malformed date is 400.
- FR-HIST-2 Sort keys `date` (default), `startedAt`, `endedAt` (open days last when ascending), `productiveSeconds`, `breakSeconds`, `spanSeconds`, `breakCount`; **default direction is `desc`** (newest first); `page` from 1, `pageSize` 1 to 100 (default 25).
- FR-HIST-3 The answer has `rows`, `total`, `page`, `pageSize`, `totals` (workdays, productive, break, span, break count, average) over the whole range, and `dailySeries` with one point for every date from `from` to `to` (zero where nothing was recorded).
- FR-HIST-4 An ended day shows its stored totals; an open day is computed to now.
- FR-HIST-5 `GET /api/workdays/{workdayId}` returns the workday with every work and break interval. It works for one's own workday and, for MANAGER and ADMIN, for a workday of somebody in scope. Any other id, another business's id or an unknown id is 404 "Workday not found."; a malformed id is 400.
- **The one average definition (personal history, person history, reports and summaries):** productive seconds divided by the number of **workdays in the range**, rounded half up; 0 without workdays. (The live board uses a different one, section 9.)
- Audit: reading one's own history or workday is not audited; opening somebody else's workday is `access.workday_viewed` (after `{date}`).

---

## 9. Module TEAM: the live board and one person (managers and owner)

**Screens.** Team (manager) and Time Tracking (owner): one page. Tiles (people, working, on break, not started, day ended, not ended, productive time today in total and on average, running live), scope control (manager: "Include indirect reports"; owner: Everybody / My direct reports / My reporting line), status filter, search, sortable columns (name, status, productive, last activity), paging. Row: name and code, manager, state with "Not ended" and the running break's length, live productive and break time, start and last activity. A row opens **one person**: name, code, manager, live state, today's totals, their history, workday detail with corrections.

**Functional requirements**
- FR-TEAM-1 `GET /api/team/members` is for ADMIN and MANAGER; EMPLOYEE and SUPER_ADMIN get 403.
- FR-TEAM-2 `scope`: MANAGER `direct` (default) or `indirect`; ADMIN `all` (default), `direct`, `indirect` (any case). Anything else, also `all` for a manager, is 400 on field `scope`.
- FR-TEAM-3 `status` filter: `WORKING`, `ON_BREAK`, `NOT_STARTED`, `DAY_ENDED` or `all` (default); else 400. `search` matches first name, last name, "first last", email, employee code (case-insensitive).
- FR-TEAM-4 Sort `name` (default), `status` (WORKING, ON_BREAK, NOT_STARTED, DAY_ENDED), `productiveSeconds`, `lastActivity` (people without activity first when ascending); ties by name.
- FR-TEAM-5 Who is on the board: the **active** people of the scope, plus a deactivated person whose workday is still open (should not exist). Each person's state is decided exactly as in `GET /api/me/time`: the open workday of any date (a night shift started yesterday is Working with yesterday's date), else today's ended day, else Not started.
- FR-TEAM-6 `metrics` (total, working, on break, not started, day ended, not ended, total productive and break seconds, average) cover **everybody of the chosen scope**; the status filter, search and paging do not change them.
- FR-TEAM-7 **Board average** = total productive seconds divided by the people who started today (working, on break or day ended), **rounded down**; 0 when nobody started. This differs on purpose from the per-workday average of FR-HIST (open point 17.6).
- FR-TEAM-8 The board is read in batches (people, workdays, work intervals, break intervals: one query each) whatever its size. It refreshes every 15 seconds while the tab is visible, on focus and when the network returns. Reading the board is **not** audited.
- FR-TEAM-9 `GET /api/team/members/{employeeId}` returns the person's employee data, `today`, state and the full workday snapshot; a deactivated person can be opened; outside the scope 404 "Employee not found."; own id 404; malformed id 400. Audited as `access.team_member_viewed`, one per request.
- FR-TEAM-10 `GET /api/team/members/{employeeId}/workdays` works like FR-HIST-1 to FR-HIST-3 for that person (default `desc`); outside scope 404. Audited as `access.team_member_workdays_viewed` (after `{range, from, to}`).
- FR-TEAM-11 The screen shows "Employee not found / This person is not in your team" for a 404.

**Audit entries.** `access.team_member_viewed`, `access.team_member_workdays_viewed`, `access.workday_viewed`, `security.out_of_scope_access` (first probe per user and window), `security.access_denied` (403).

---

## 10. Module COR: time corrections (managers and owner)

**Screens (on the person's page).** Workday detail with the intervals; edit an interval's start and end (a running one only its start) with a mandatory reason and a preview of the new totals; close an open workday (optional end time, reason, confirmation). Date and time are typed in the display time zone and sent as UTC instants. Refusals appear next to the field.

**Functional requirements**
- FR-COR-1 Endpoints: `PATCH /api/workdays/{workdayId}/work-sessions/{sessionId}`, `PATCH /api/workdays/{workdayId}/break-sessions/{sessionId}`, `PUT /api/workdays/{workdayId}/status`. ADMIN or MANAGER within the scope; EMPLOYEE and SUPER_ADMIN get 403.
- FR-COR-2 **Nobody corrects their own time**, the owner included: 403 `CANNOT_CORRECT_OWN_TIME`. A manager's time is corrected by their own manager or the owner.
- FR-COR-3 `reason` is mandatory, 5 to 500 characters after trimming (400 field `reason`).
- FR-COR-4 Times are ISO-8601 instants with `Z` or an offset, stored in UTC; anything else is 400. Times have millisecond precision: a time sent back exactly as read is no change.
- FR-COR-5 No time may lie in the future (400). An interval must end after it starts (400 field `endedAt`).
- FR-COR-6 Intervals of one workday never overlap: work with work, break with break **and** work with break (422 `TIME_INTERVAL_OVERLAP`); touching is fine; only the latest interval may run. The first interval of a day is a work interval (422 `WORKDAY_MUST_START_WITH_WORK`).
- FR-COR-7 **Window.** A corrected time, and the end of a closed workday, must lie from the start of the workday's date to the end of the **next** date, in the zone the workday was started in, and the first interval must start on the date itself; else 422 `CORRECTION_OUT_OF_RANGE`. A wrongly typed year can no longer add years of work or move hours into another day.
- FR-COR-8 **Other workdays.** The corrected workday (first start to last end, or to now while it runs) must not overlap another workday of the same person: 422 `WORKDAY_OVERLAP` (the same hours would count twice). Touching is fine.
- FR-COR-9 A running interval keeps running: only its start moves; a value for `endedAt` is 422 `INTERVAL_STILL_RUNNING`. A closed interval stays closed (`endedAt: null` is 400).
- FR-COR-10 After a correction the workday's start is the start of its first interval and, when ended, its end is the end of its last. Its date never changes. An ended day stays ended. Stored totals are recomputed from the intervals.
- FR-COR-11 If the times equal the stored ones nothing is written and the answer is 200 "Nothing changed.". Success is 200 "Time corrected." with the workday.
- FR-COR-12 `PUT /api/workdays/{workdayId}/status` accepts only `DAY_ENDED`: it ends an open (typically forgotten, "Not ended") workday. Without `endedAt` the end is now while an interval is running, otherwise the end of the last interval; `endedAt` may not be in the future nor before the last recorded activity, and must be in the window (FR-COR-7). Already ended: 200 "This workday had already ended." and no entry. Afterwards the employee may start again; a day ended on today's date is reopened by their next START.
- FR-COR-13 Each correction locks the employee first (as a time event), so it never interleaves with the employee's own start, break, resume or end.
- FR-COR-14 **Error order:** 401/403 role, 400 body, 404 (unknown workday, other business, out of scope) or 403 `CANNOT_CORRECT_OWN_TIME`, 404 interval ("Time record not found." also for a break id on `work-sessions`), then the rules above (400/422).

**Audit entries.** `time.session_adjusted` (actor the corrector, subject the employee, entity `work_session` or `break_session`, reason, interval and workday totals before and after), `time.workday_closed` (reason, closed intervals), `time.correction_refused` (DENIED, the 422 code as reason, `after.operation` = `adjust_work_session`, `adjust_break_session` or `close_workday`; written after the rollback so it stays).

**Edge cases.** Night shift: a break may end after midnight inside the window. DST: the window is computed in the workday's zone. Business suspended: no correction possible (403). Deactivated employee: their past days can still be corrected.

---

## 11. Module REP: reports and CSV export (managers and owner)

**Screens.** Reports (one page for both roles): tabs *Workdays* and *Summaries*, filters (range, scope, person, manager of the day, status, search), totals cards, daily chart, tables, *Export CSV*.

**Functional requirements**
- FR-REP-1 `GET /api/reports/workdays` and `GET /api/reports/summaries` are for ADMIN and MANAGER; EMPLOYEE and SUPER_ADMIN get 403.
- FR-REP-2 Parameters: `range` (`today`, `yesterday`, `week` (default), `month`, `custom`), `from`, `to` (rules as FR-HIST-1), `scope` (`direct`, `indirect`, `all`; default `all` for the owner, `direct` for a manager), `employeeId`, `managerId`, `status`, `search`, `sort`, `sortDirection`, `page`, `pageSize`. `scope=all` for a manager is 403 "You can see only the people who report to you."
- FR-REP-3 `employeeId` must be in scope, else 404 "Employee not found."; `managerId` filters on the **manager of the day** (the workday's snapshot): a manager may give themselves or someone in scope, the owner with `scope=all` anyone of the business, else 404 "Manager not found.".
- FR-REP-4 `status` takes one or more of `WORKING`, `ON_BREAK`, `DAY_ENDED`, `NOT_ENDED` (comma-separated). `NOT_ENDED` = an open day that is stale; such a day also matches `WORKING` or `ON_BREAK`.
- FR-REP-5 Every bad parameter is reported at once as 400 `VALIDATION_FAILED` with `errors[].field`. Order of checks: 400, then 403, then 404; for the CSV the `timeZone` check (400) and the row cap (422) come after.
- FR-REP-6 **What counts.** A workday belongs to the date it **started** on in the business time zone. Deactivated people stay in scope. The owner's `all` includes the owner. An ended day shows its stored totals, an open day is computed to the request time. `totals` and `dailySeries` cover the whole range (all pages) and always add up to the rows; `dailySeries` has a point for every date.
- FR-REP-7 *Workdays* rows: date, employee (id, code, name), manager of the day, status, `isStale`, start, end, productive, break, span, break count. Sort `date` (default), `employee`, `startedAt`, `productiveSeconds`, `breakSeconds`, `spanSeconds`, `breakCount`; default direction `desc`.
- FR-REP-8 *Summaries* `groupBy` = `employee` (default), `manager` (manager of the day; workdays without one form the group `none`, always last) or `date`; rows add workdays, employees, productive, break, span, break count, average per workday, first start, last end. Default direction `asc`, `desc` for `groupBy=date`.
- FR-REP-9 **CSV.** The workdays report with `Accept: text/csv` returns **every row of the range** in the requested order (paging ignored). Extra parameter `timeZone` = the business time zone (default) or one of its display zones (any case); anything else is 400 on `timeZone`.
- FR-REP-10 File format: `text/csv;charset=UTF-8`, byte order mark, CRLF, `Content-Disposition: attachment; filename="juristrack-<from>-to-<to>.csv"`, `Cache-Control: no-store`. Header `Date,Employee,Employee ID,Manager,Status,Start (<zone>),End (<zone>),Productive hours,Break hours,Span hours,Breaks`; status words `Working`, `On Break`, `Day Ended`, `Not ended`; start and end as `HH:mm` in the chosen zone; hours with two decimals.
- FR-REP-11 **Formula safety.** A cell starting with `=`, `+`, `-`, `@`, tab or CR gets a leading `'`; a cell with `"`, `,`, LF or CR is quoted.
- FR-REP-12 **Limit.** More than 50,000 rows (`juristrack.reports.maximum-export-rows`) answers 422 `EXPORT_TOO_LARGE` ("... Choose a shorter date range or fewer people."); nothing is exported or audited.
- FR-REP-13 A report filtered on the caller themselves (owner only) reads one's own data and is not audited.

**Audit entries.** `reports.viewed` (entity `report`, after `{report, filters, rowCount}`; one per request, never one per row), `reports.exported` (adds `timeZone`, `fileName`), `security.access_denied`, `security.out_of_scope_access`.

---

## 12. Module AUD: the audit log

**Principle (owner decision 2026-10-23).** Every movement is recorded; nothing is left out because it is small.

**Screens.** Audit Log (owner: `admin/audit-log`; super admin: `platform/audit`, same page): table (time, business for the super admin, actor and role, subject, action as a readable label, category and outcome chips, record, source and IP, request id with copy), filters kept in the URL, active-filters bar with *Clear all*, side panel with every field and the record before/after as a field-by-field diff, *Export CSV*. No edit or delete controls.

**An entry holds**: time (UTC), business (null = platform event), actor and the actor's role at that moment, subject, action, category (`auth`, `people`, `business`, `time`, `security`, `access`, `integration`, `audit`), outcome (`SUCCESS`, `DENIED` = refused for a rule, role or security reason, `FAILED` = allowed but went wrong), entity type and id, before and after (JSON), reason, source (`STANDALONE`, `INTEGRATION`, `SYSTEM`), IP address, user agent (400 characters), request id, HTTP method, path (never the query string).

**Functional requirements**
- FR-AUD-1 **Append-only.** The application never updates or deletes an entry; the database refuses `UPDATE`, `DELETE` and `TRUNCATE` on `audit_logs` for every role including the schema owner (trigger), and the business role has no update or delete rights. A user or business that appears in the log cannot be deleted.
- FR-AUD-2 **Same transaction.** A write is audited in the same transaction as the change. Refusals and failures are written in a transaction of their own so they stay although the request fails. Reads of other people's data write their entry in the read's own transaction (the data is never served without its entry).
- FR-AUD-3 **Never stored**: sign-in codes, tokens, cookies, secrets. JSON values under keys that name a password, token, secret, cookie, authorization or one-time code are replaced by `"[redacted]"`; the email of an unknown account is stored masked (`a***@example.com`).
- FR-AUD-4 **Volume rules.** One entry per write; reads of someone else's data one entry per request (the live board, which refreshes every few seconds, is not recorded). Never audited: reading one's own data (`/api/me`, `/api/me/time`, own history and workday, own employee record, `GET /api/business`), a successful session refresh, a 401 without a session or with an expired access token, validation errors and conflicts (400, 409), a 404 for a record in one's own scope that does not exist, and *viewing* the audit log (only exports are).
- FR-AUD-5 High-volume security events (rate limits, refused refreshes, bad cookies, refused tokens, suspended-business requests, cross-site writes, out-of-scope probes) are written at most once per key in each 15-minute window (in memory per API instance; `after.aggregationWindowMinutes`). Entries nobody signed in caused are capped per IP address and window (500 by default).
- FR-AUD-6 **Out-of-scope probe.** A 404 for somebody else's person or workday (team, workday, correction, report and employee endpoints) writes `security.out_of_scope_access` (DENIED, reason `NOT_FOUND`, `after.recordType`, no subject because the probed id is not trusted), once per user and window.
- FR-AUD-7 **Who sees what.** The owner sees only their business's entries (row-level security); the super admin sees every entry, platform entries (no business) only there. MANAGER and EMPLOYEE get 403.
- FR-AUD-8 `GET /api/audit-logs` filters: `from`, `to` (inclusive; owner's business time zone, super admin UTC; reversed ends swapped), `actorId`, `subjectId`, `action` (exact, or a prefix when it ends with a dot, e.g. `time.`), `category`, `entityType`, `entityId`, `outcome`, `source`, `search` (action, actor and subject name, request id, entity id, IP address); super admin only `businessId` (an id, or `none` for entries outside any business). Sort `createdAt` (default), `action`, `category`, `outcome`, `actor`, `subject`; **default `desc`**. A malformed value is 400 with the parameter as `field`.
- FR-AUD-9 Names are resolved when read (a renamed person shows the current name). `summary` is machine-made: changed fields of an update, else the reason of a refusal or failure.
- FR-AUD-10 `GET /api/audit-logs/{auditLogId}` returns the full entry (adds before, after, user agent, method, path); 404 for an unknown id or another business's entry (owner); not audited.
- FR-AUD-11 **CSV export** (`format=csv`): same filter and sort, every row, **at most 10,000 rows**, else 422 `EXPORT_TOO_LARGE`; any `format` other than `json`/`csv` is 400. UTF-8 with byte order mark, CRLF, file `audit-log-<today>.csv`, every cell quoted, formula guard as FR-REP-11. Columns: Time (UTC), Business, Actor, Actor role, Subject, Action, Category, Outcome, Entity type, Entity id, Source, IP address, Request id, Method, Path, Reason, Summary, Before, After (JSON). Audited as `audit.exported`.
- FR-AUD-12 The screen does not follow the export link above the cap and explains it instead (a probe request would itself be audited).
- FR-AUD-13 **Retention.** Entries are kept for good; no automatic deletion. Pruning is a database-owner operation outside the application (drop the trigger, delete, recreate) and must itself be documented.

**Catalogue of action names (all of `AuditAction`)**

| Category | Actions |
|---|---|
| auth | `auth.sign_in_code_requested`, `auth.signed_in`, `auth.sign_in_failed`, `auth.sign_in_code_burnt`, `auth.rate_limited`, `auth.signed_out`, `auth.session_revoked_refresh_reuse`, `auth.session_refresh_failed` |
| security | `security.access_denied`, `security.suspended_business_request`, `security.invalid_session_cookie`, `security.out_of_scope_access`, `security.cross_site_request_blocked` |
| access | `access.employee_list_viewed`, `access.employee_viewed`, `access.business_list_viewed`, `access.business_viewed`, `access.team_member_viewed`, `access.team_member_workdays_viewed`, `access.workday_viewed`, `reports.viewed`, `reports.exported` |
| audit | `audit.exported` |
| integration | `integration.business_provisioned`, `integration.business_linked`, `integration.business_unlinked`, `integration.business_updated`, `integration.user_provisioned`, `integration.user_linked`, `integration.user_updated`, `integration.token_rejected`, `integration.user_refused` |
| business | `business.created`, `business.updated`, `business.suspended`, `business.reactivated` |
| people | `employee.created`, `employee.updated`, `employee.deactivated`, `employee.reactivated`, `employee.change_refused` |
| time | `time.workday_started`, `time.workday_reopened`, `time.break_started`, `time.work_resumed`, `time.workday_ended`, `time.event_failed`, `workday.closed`, `time.session_adjusted`, `time.workday_closed`, `time.correction_refused` |

(The reports actions carry category `access`; the category is its own column.)

---

## 13. Module TZ and I18N: display time zone and languages

- FR-TZ-1 All instants are stored and sent in UTC. Dates (`YYYY-MM-DD`) are calendar dates in the **business time zone**. Durations are whole seconds computed from the recorded intervals.
- FR-TZ-2 The top bar's "Show times in" switcher (every user of a business) offers the business time zone and the business's display time zones (up to 8), each with its current time. The choice is per browser (`localStorage` key `juristrack.displayTimeZone`; when storage is blocked it lasts until reload). It changes **clock times** on the time pages (starts, ends, intervals, board times, correction forms, audit time); workday dates, date ranges and durations never change. The super admin gets the browser's zone and no choice.
- FR-TZ-3 The CSV `timeZone` is chosen at export time and named in the header (e.g. `Start (IST)`).
- FR-TZ-4 The business time zone is set by the owner or super admin and applies to workdays started afterwards; a workday remembers the zone it started in.
- FR-I18N-1 The interface is available in English and Hindi. The English text is the translation key; a missing Hindi text shows the English text. A language switcher is in the shell. API messages (`detail`) are mapped to clear screen messages by problem `code`.
- FR-I18N-2 Dates, times and durations are formatted by the app, not the API (the API returns raw values).

---

## 14. Non-functional requirements

**Security**
- NFR-SEC-1 Sign-in only by emailed code; tokens and codes are stored hashed; cookies are `HttpOnly`, `Secure` where https (uat, prd); the API refuses to start in sqe/uat/prd with a fixed code, demo data, an `http://` app URL, a missing secret or no profile.
- NFR-SEC-2 **Multi-business isolation.** Every business table carries `business_id` and PostgreSQL **row-level security** (forced) limits each connection to its business (`app.business_id`), identically for cookie and token callers. Only the platform (system mode) sees across businesses. Another business's record answers 404.
- NFR-SEC-3 Every rule is enforced by the API on every request (role, scope, business); out-of-scope answers 404.
- NFR-SEC-4 Origin check on cookie writes; CORS only for the web app's origin; body limit 64 KB; formula guard on CSV; no secrets, codes or tokens in logs or the audit log.
- NFR-SEC-5 Trusted proxies (FR-AUTH-14) so that rate limits and the audit IP cannot be forged.
- NFR-SEC-6 Embedded: single-use, short-lived, signed tokens (section 15).

**Performance**
- NFR-PERF-1 Lists are paged (default 25, at most 100). The board and reports are read set-based (one query per kind of data), whatever the number of people; index on workdays by business and date.
- NFR-PERF-2 The personal timer polls every 30 seconds, the board every 15 seconds, only while the tab is visible.
- NFR-PERF-3 Requests of one person are serialized by a row lock; others are never blocked.

**Accessibility and usability**
- NFR-A11Y-1 Interactive elements are native buttons, links and inputs with labels; rows open with click or Enter; alerts use `role="alert"`; the app works on desktop, laptop and phone width. (No formal WCAG audit has been done: open point 17.9.)

**Languages.** English and Hindi (FR-I18N-1).

**Reliability and operations.** Schema owned by Flyway (migrations V1, V2, V3, V11, each with a rollback; the next is V12; applied migrations are never edited). Backups with `scripts/backup.sh` / `restore.sh` (14 days). Health at `/actuator/health`. Environments dev, sqe, uat, prd with their own ports and databases. Rate limits and replay guards are in memory per instance, so a second API instance needs a shared store (open point 17.8).

---

## 15. Integration with JurisOne (summary)

The full picture, diagrams and the token step by step are in [FLOW-JurisTrack-JurisOne-Connection.md](FLOW-JurisTrack-JurisOne-Connection.md); the design is in `INTEGRATION.md`. In short:

- JurisOne's backend is a **gateway**: it checks its own session, the business's plan (module `JURIS_TRACK`) and permission, signs a short JWT for **each request** and forwards `Authorization: Bearer <token>` (plus `X-Request-Id`) to the JurisTrack API, which is reachable only from the gateway. The browser talks only to JurisOne.
- Token (ES256/RS256, verified with JurisOne's public key): `iss`, `aud`, `sub` (JurisOne employee id), `email`, `name`, `businessId`, `businessName`, optional `managerId`, optional `trackRole`, and `exp`, `iat`, `jti`.
- FR-INT-1 **Single use:** a `jti` is accepted once; a replay answers 401 and is audited (`integration.token_rejected`, reason `replayed_token`). Missing `iat` or `jti` is refused. **Lifetime** `exp - iat` is at most 10 minutes (about 5 in practice), else `lifetime_too_long`. Clock skew allowed: 30 seconds.
- FR-INT-2 Any invalid token answers 401 without details and is never logged; the audit entry holds only a reason code (`bad_signature`, `expired`, `wrong_issuer`, `wrong_audience`, `malformed_token`, `missing_issue_time`, `lifetime_too_long`, `replayed_token`, `missing_claim: <name>`).
- FR-INT-3 **Just-in-time provisioning:** the business is found by `jurisone_business_id` (created with defaults if new, renamed if JurisOne's name changed), the user by `jurisone_employee_id`, else by email **inside that business**, else created. **A token never adopts an existing standalone business by email**; only the super admin's explicit link (FR-BIZ-8) does, because otherwise any standalone owner could plant a JurisOne colleague's email and take over that company.
- FR-INT-4 The token is authoritative on every call for name, email, role and manager: `trackRole` `MANAGER` or `EMPLOYEE` (anything else is EMPLOYEE; `ADMIN` only when sent), `managerId` resolved by the manager's JurisOne id within the business. Exception: an existing ADMIN keeps the owner role unless the token itself says otherwise. An unchanged user writes no entry.
- FR-INT-5 Refused with 403: a deactivated user, an email that belongs to a user of another business or to the super admin (`integration.user_refused`, reasons `user_deactivated`, `email_of_another_business`, `conflict`; no business is created), a user of a suspended business (`BUSINESS_SUSPENDED`).
- FR-INT-8 A user whose login the owner switched off (FR-EMP-10) is refused with 403 in the same way (`integration.user_refused`, reason `login_disabled`); the check comes before any change from the token is applied.
- FR-INT-6 The Bearer token is accepted only when `juristrack.integration.enabled=true` and a public key is configured; requests are audited with source `INTEGRATION`.
- FR-INT-7 Embedded, JurisTrack's pages (`src/track`) run inside JurisOne's shell through an injected `TrackContext` (API base path `/api/juris-track`, user, translate function, navigate, toast, `onUnauthorized`, `onBusinessSuspended`). The embedding itself is not built yet.
- The roster push `PUT /api/integration/employees` is **not built** (open point 17.1).

---

## 16. Out of scope

- Passwords, password reset, forced password change (sign-in is by code only).
- Teams as objects, team membership history and team reports (replaced by the reporting line).
- Payroll, leave, shifts, holidays, attendance policy, overtime rules, notifications, email reminders for forgotten days.
- Automatic ending of a forgotten workday (it is flagged "Not ended" and ended by a manager or the owner).
- Self-service sign-up of a company (the super admin creates businesses).
- Roster sync from JurisOne (`PUT /api/integration/employees`), a JWKS key URL, billing and subscriptions of standalone companies.
- Changing or deleting audit entries; deleting users or businesses.
- Importing data from the old Next.js app (fresh start).
- The JurisOne side of the integration (gateway, plan check, menu); the identity provider work.
- Native mobile apps.

---

## 17. Open points (code and documents that disagree, or are still undecided)

1. **Roster sync.** `INTEGRATION.md` calls `PUT /api/integration/employees` "part of the first version"; `MIGRATION_PLAN.md` says "not built yet" and there is no such controller. Until it exists, an embedded manager sees only reports who have opened JurisTrack at least once through a token.
2. **Brief vs product.** `timetrack.txt` (original brief) has Admin who does not track time, teams, passwords, a Next.js stack, and lets "End workday" on a break be handled either way. The product now has the owner tracking own time, no teams, emailed-code sign-in, and refuses END on a break (409 `BREAK_IN_PROGRESS`). This FRD follows `API.md` and the code.
3. **Status name.** The database enum `workday_status` stores `COMPLETED`; the API and screens say `DAY_ENDED` (the brief says "completed"). Same thing, two names.
4. **`workday.closed` comment.** The `AuditAction` Javadoc says the entry is written "so far only when the employee is deactivated"; the code also writes it when a business is suspended (as `API.md` says). Documentation drift in a code comment.
5. **Scope error for managers.** `scope=all` from a MANAGER is 400 on the Team board (field `scope`) but 403 `FORBIDDEN` on reports (`API.md` states both). Intended per the contract, but inconsistent.
6. **Two averages.** The board's average divides by people who started today and rounds down; the history, person history, reports and summaries divide by workdays and round half up. Intended (documented in `API.md` "Averages"), but a tester can mistake it for a bug.
7. **Missing document.** The frontend `README.md` points to `references/API-team-and-corrections.md` in the backend repo; that file does not exist (its content is merged into `API.md`). The same README still says "the other pages are Coming soon placeholders": in the code only the owner's **Overview** (`/admin`) is a placeholder; the other pages are built.
8. **Single instance.** Rate limits, the aggregation windows of audit events and the integration `jti` replay guard live in memory of one API instance. With more than one instance the limits and the replay guard are per instance.
9. **Accessibility** has no formal target or audit recorded in any source; NFR-A11Y-1 only records what the code does.
10. **Owner invitation.** There is no invitation email: the owner learns of their account from the super admin and requests a code. Whether a "welcome" email is wanted is not decided in any source.
11. **Per-user display time zone** was open as D6 in `MIGRATION_PLAN.md`; the frontend keeps it per browser (`localStorage`). Not stored on the server.
12. **Language default.** `MIGRATION_PLAN.md` question D8 proposed "English only"; the product has English and Hindi (the plan's decision table lists D8 as English + Hindi).
