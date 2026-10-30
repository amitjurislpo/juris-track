# JurisTrack: standalone and embedded in JurisOne

Owner's plan: **JurisOne** is the complete system and JurisTrack becomes a part of it, but JurisTrack is also **sold on its own**. So one codebase
(this backend + `juris-track-frontend`) must run in two modes. Nothing is changed in the JurisOne repos until the owner says so; this file is the design
the integration will follow.

## 1. The two modes

| | Standalone | Embedded in JurisOne |
|---|---|---|
| Who signs in | Employees of a company that bought only JurisTrack | Employees of a JurisOne business that has the Juris Track module |
| Login screen | JurisTrack's own (email + 6-digit emailed code) | JurisOne's (unchanged) |
| Session | JurisTrack cookies (`jt_at` access JWT, `jt_rt` rotating refresh) | JurisOne's session; JurisTrack never sees the cookies |
| UI shell | JurisTrack's own shell (`src/standalone` in the frontend) | JurisOne's shell; JurisTrack pages (`src/track`) open inside it |
| API caller | the browser, through the web app's `/api/v1` proxy | the JurisOne backend (gateway) |
| Users and business | created and managed in JurisTrack (admin, or sign-up of the company) | created just in time from the JurisOne token |
| Database | JurisTrack's own (`juristrack`) | the same JurisTrack database/service, one row set per business |

Both modes use the same endpoints, the same permission rules and the same tables. Only *how the caller is authenticated* differs.

## 2. Embedded: how a JurisOne user reaches JurisTrack (service-to-service token)

```
Browser (JurisOne UI, JurisOne cookies)
   |  GET/POST /api/v1/juris-track/**             (same origin as JurisOne, extends the existing placeholder path)
   v
JurisOne backend  = gateway (BFF)
   |  1. checks the JurisOne session, the business plan/grant (module JURIS_TRACK) and the user's JurisOne permission
   |  2. signs a short-lived JWT for this call
   |  3. forwards the request:  Authorization: Bearer <jwt>   (+ X-Request-Id, original method/path/query/body)
   v
JurisTrack API  /api/v1/**  (internal network only, never exposed to browsers in this mode)
   |  verifies the JWT, provisions the business/user if new, applies its own rules, answers
```

Why a gateway and not the browser calling JurisTrack with a token: one origin (cookies, the Origin/CSRF check and CORS stay as today), the signing key never
leaves the JurisOne backend, JurisOne keeps control of who may use the module (plan/grant, suspension), and JurisTrack needs no knowledge of JurisOne sessions.

**Token (JWT, asymmetric):** signed by JurisOne with a private key (ES256 or RS256), verified by JurisTrack with the public key only (configured as
`juristrack.integration.public-key`, later a JWKS URL so keys can rotate). Lifetime about 5 minutes (at most 10: `exp - iat`), **one token per request** (single-use, see `jti`).

| Claim | Meaning |
|---|---|
| `iss` | `jurisone` (must equal `juristrack.integration.issuer`) |
| `aud` | `juristrack` (must equal `juristrack.integration.audience`) |
| `sub` | JurisOne employee id (stable) |
| `email`, `name` | for provisioning and display |
| `businessId`, `businessName` | JurisOne business id and name |
| `managerId` (optional) | JurisOne employee id of this employee's assigned manager (null if none): JurisTrack keeps it as `manager_id` and refreshes it on every token |
| `shiftCode` (optional, **built**) | The code of the shift JurisOne has assigned to this employee (a `code` from the roster's `shifts` list). A first-time user who signs in before any roster push gets this shift assigned from today, so their first workday is attributed to the right shift. Later changes come with the roster; a claim never changes an assignment that already exists (only a user this very token creates gets it; an unknown, blank or non-text code is ignored) |
| `trackRole` (optional) | `MANAGER` when JurisOne has granted this user the **JurisTrack manager** role, otherwise `EMPLOYEE` (optional `ADMIN` only if JurisOne wants an owner-type user). Sent on **every** token; JurisTrack follows it, so granting or removing the role in JurisOne takes effect on the next call |
| `exp`, `iat`, `jti` | all three required: expiry (short, `exp - iat` at most 10 minutes), issue time, unique id. A `jti` is accepted **once**: a replayed token answers 401 and is audited (`replayed_token`), so the gateway issues a fresh token for every request |

**Just-in-time provisioning (implemented):** on a valid token, in system mode inside one transaction: find the business by `businesses.jurisone_business_id` (create it
with the JurisOne business name and defaults if new, refresh the name otherwise), then the user by `users.jurisone_employee_id`; if not found, match by email
**inside that business** (a user of the linked business who was added by email before is linked), else create the user. **A token never adopts an existing
business.** A standalone company moves to JurisOne only when the platform super admin links it explicitly (`PUT /api/v1/businesses/{businessId}/jurisone-link`,
audited; `DELETE` undoes it); otherwise any standalone owner could plant an employee with a JurisOne colleague's email, and the first token of that company
would hand the whole company to the planter. **The token is authoritative on every call** for name, email, role (`MANAGER` or `EMPLOYEE`; `ADMIN` only when
sent; a downgrade takes effect on the next call, **except that an existing ADMIN keeps the owner role** unless the token itself says otherwise, so linking
can never leave a business without its owner) and manager (`manager_id` is resolved by the manager's `jurisone_employee_id` in the same business; null when the
manager is not known yet; never the user themself). Granting or removing the JurisTrack manager role in JurisOne therefore needs no action in JurisTrack. An
unchanged user writes no audit entry. Rules: if the token's JurisOne business is not known yet and the email (or the employee id) already belongs to a user
anywhere, the token is refused with 403 (`integration.user_refused`, reason `email_of_another_business`; no business is created for it); an email that belongs
to the `SUPER_ADMIN` is refused with 403; a deactivated user, or one whose login the owner switched off, is refused with 403; a user of a **suspended business** is refused with 403 `BUSINESS_SUSPENDED`
(the super admin suspends businesses, which also closes their open workdays); any invalid token answers 401 without details and is never logged.

**Roster sync (BUILT, version 2.1 of the documents):** a manager must see all their reports, including those who never opened JurisTrack, so JurisOne also pushes the
roster. `PUT /api/v1/integration/employees` (service token: `aud=juristrack`, `scope=employees:sync`, issued by JurisOne) takes the list of a business's employees
{ id, code, name, email, managerId, isActive } and creates/updates/deactivates users accordingly (idempotent, whole roster or a batch of changes). JurisOne calls
it when employees or manager assignments change and on a schedule. Just-in-time provisioning from the user token still works as a fallback.

**Service token of the roster sync (built).** JurisOne itself is the caller, not a user, so the token has no person in it: signed with the same private key
and checked like a user token (signature, algorithm, `iss`, `aud`, `exp - iat` at most 10 minutes, clock skew, single-use `jti`), plus:

| Claim | Meaning |
|---|---|
| `iss`, `aud`, `iat`, `exp`, `jti` | as for a user token (`aud` = `juristrack`) |
| `scope` | **required, exactly `employees:sync`** |
| `businessId`, `businessName` | the one JurisOne business this call is about (the body is about that business only) |
| `sub`, `email` | **must be absent**: a token with either is refused (`not_a_service_token`) |

The service token opens **only** `PUT /api/v1/integration/employees` (matched exactly); a user token (no scope), a cookie session or a token with another scope
cannot call that endpoint, and a user token that carries a `scope` is refused everywhere else, so a service token never works on any other endpoint. A
refused token answers 401 without details and is audited as `integration.token_rejected` (`missing_scope`, `wrong_scope`, `not_a_service_token`,
`service_token_not_accepted`, ...). The business is found by `jurisone_business_id` or created (never an existing standalone business); a suspended
business answers 403 `BUSINESS_SUSPENDED`. The answer lists skipped people with reason codes instead of failing the batch (`API.md`, "Roster sync":
body, reason codes, response, limits of 5000 employees and 62 schedule entries per person, audit). A person deactivated by the sync is reactivated by it
only; the owner's decisions (a deactivation, a switched-off login) are kept. JurisOne keeps its own role authority through the user token on every later
call; the sync never makes anybody `ADMIN` and an existing owner keeps the role.

**Roster contract including shifts (BUILT; specified in version 2.0, built in version 2.1 of the documents).** JurisTrack calculates time per shift (`API.md`, "Shifts and the
roster"). JurisOne onboards employees too, so it sends their shift in the same request. Body (JSON, one business per call, the business is the one the
service token names):

```json
{
  "shifts": [ { "code": "US", "name": "US shift", "startTime": "19:30", "endTime": "04:30", "earlyStartMinutes": 180, "lateEndMinutes": 180 } ],
  "employees": [ { "employeeId": "<JurisOne id>", "email": "a@b.c", "firstName": "A", "lastName": "B", "employeeCode": "E1", "role": "EMPLOYEE",
                   "managerEmployeeId": "<JurisOne id>", "isActive": true, "shiftCode": "US",
                   "schedule": [ { "date": "2026-11-02", "shiftCode": null }, { "date": "2026-11-03", "shiftCode": "UK" } ] } ]
}
```
- `shifts`: the business's shift templates by a JurisOne-chosen `code` (unique per business). JurisTrack creates or updates the shift with that code
  (name, times, grace; changes affect future occurrences only) and remembers the code on it; a shift missing from a later call is left as it is.
  Times are `HH:mm` on the **business time zone's** wall clock; an end at or before the start means the next day; the grace defaults to 180 minutes.
- `employees[].shiftCode`: the shift assigned to the employee from today on (the same assignment as `PUT /api/v1/employees/{id}/shift`; the history is
  kept; the same shift again changes nothing). Absent or null: the assignment is left as it is.
- `employees[].schedule`: the roster for up to the next 62 days, one entry per date; `shiftCode` null is a **week off** (weeks off are rotational).
  Entries are the same records as `PUT /api/v1/employees/{id}/schedule-entries`: idempotent upsert per date, a date that already has a workday is skipped
  and reported, dates beyond 365 days are refused. Dates not listed are left as they are.
- Idempotent: the same body twice changes nothing and writes no further audit entry beyond `integration.roster_synced` (counts only). Every changed
  shift, assignment or roster entry is audited like the manual change (`shift.*`, `employee.shift_changed`, `schedule.entry_set`) with source `INTEGRATION`.
- JurisOne is the master of the roster when the business is linked (open point: who is master while both sides can edit; until then the owner's
  screens in JurisTrack stay editable and the next push wins for the dates it lists).

**Audit of the integration:** Bearer requests are audited with source `INTEGRATION`; every roster sync call writes one `integration.roster_synced` entry (counts only) besides the usual entry of each change it makes; the gateway may pass an `X-Request-Id` that is stored with the entries; refused tokens (`integration.token_rejected`, reason code only, never the token) and refused users (`integration.user_refused`) are audited, and a renamed linked business is audited as `integration.business_updated`.

**Security rules:** accept the Bearer token only when `juristrack.integration.enabled=true` (a service token only on the roster sync, a user token on every other endpoint); reject unsigned, expired or wrong `iss`/`aud` tokens; allow a small
clock skew (`juristrack.integration.clock-skew-seconds`); in embedded deployments the JurisTrack API port is reachable only from the JurisOne backend (network
rule or mTLS); never log tokens; a `jti` is accepted once (replay guard, in memory per instance); put the gateway's address in `juristrack.security.trusted-proxies` so the
client address it forwards in `X-Forwarded-For` is believed (rate limits and the audit IP follow it). Bearer requests skip the Origin check: in embedded mode the
cross-site defence is the JurisOne gateway's job.

## 3. Standalone: how a direct user signs in

Same flow as JurisOne's own sign-in: `POST /api/v1/auth/otp-requests` (email), then `POST /api/v1/auth/sessions` (email + code); cookies `jt_at` / `jt_rt`; fixed code
`000000` only in dev. Settings (all in `application-<env>.properties`): `juristrack.otp.code-ttl-minutes` (10), `max-attempts` (5, then the code is burnt), `requests-per-ip` (10) and `requests-per-email` (5) per `window-minutes` (15), `failed-sign-ins-per-ip` (20); `juristrack.security.access-token-ttl-seconds` (300), `session-ttl-days`, `short-session-hours` (12, when "keep me signed in" is off), `refresh-reuse-grace-seconds`, `cookie-secure` (true in uat/prd). Limits are in memory (one instance). Inactive or unknown emails get the same generic answer and no code. Email addresses are globally unique, so the business is resolved from the email. The first super admin comes from the `seed` command; the super admin then creates
businesses and their owners (open question 2), and owners add their employees.

## 3b. Why JurisTrack is integrated, and who sees what (same in both modes)

**Main purpose:** a manager sees, for each of the employees who report to them, who is working or on break right now, who worked how long, how many breaks and
how long, and the productive time. Every user, managers included, also **tracks their own time** (start work, break, resume, end day) and sees their own history;
that function and its UI are the same for everybody.

**The unit of "team" is the reporting line**, not a separate team object: every user has at most one `manager_id` (the person they report to). In JurisOne this is
the manager assigned to the employee; in standalone the owner assigns it when adding an employee.

| Role | Standalone (any company that bought only JurisTrack, small or large) | Embedded in JurisOne |
|---|---|---|
| `SUPER_ADMIN` | Platform user of the product owner. Belongs to no business. Creates businesses and their owner. | Not used (JurisOne's own super admin manages businesses). |
| `ADMIN` | The business **owner**: adds employees, assigns managers, sees everyone, corrects time, reports, audit log, settings. | Not required. Only if JurisOne hints it. |
| `MANAGER` | The **JurisTrack manager**: sees the live board, history and reports of the people in their scope, and can correct their time. | One role, **granted in JurisOne** ("JurisTrack manager") and sent as `trackRole=MANAGER` on every token. Whoever has it sees the people under them in JurisOne's reporting line. Without the role nobody sees anybody else, even with reports. |
| `EMPLOYEE` | Every added employee: signs in with the emailed code and tracks time at once. | **Every JurisOne employee, whatever their JurisOne role (HR, Finance, ...).** |

No HR, Finance or other functional roles exist in JurisTrack. "Individual" (small company) mode is simply standalone mode: a company that bought only JurisTrack.

**Scope of a manager (decided):**
- Default: **direct reports** only (users whose `manager_id` is the manager).
- A switch **"Include indirect reports"** in the Team tab adds the people one level further down (the reports of their reports). So a "super manager" (the
  manager of managers) can see everybody under their managers, at the cost of one click. The depth is a parameter (1 = direct, 2 = direct + one level below)
  so it can be widened later without redesign.
- A manager also sees **their own** record in the personal section. They never see the people above them or beside them.

**Corrections (decided):** in **both** modes a manager (and in standalone also the owner) can correct the time of the people in their scope (adjust a work or break
interval, close a forgotten workday), **never their own**. A reason of at least 5 characters is mandatory, overlaps/future/inverted intervals are rejected, and
the change is written to the audit log in the same transaction. When a manager's own time needs correcting, their manager (or the owner) does it.

**UI structure for every user:** a personal **section** (Dashboard, My time, My history, Profile; endpoints `/api/v1/me/time`, `/api/v1/me/time-events`, `/api/v1/workdays/mine`, `/api/v1/workdays/{id}`) that is always there, even for a manager; a manager additionally
gets the **Team tab** (live board with working / on break / not started / day ended, productive time per person, reports, each report's history, the indirect-reports
switch). The same pages in both modes, so the design is identical when embedded.

## 4. What the code must look like (so both modes work)

**Backend**
- One `CurrentUser` model (user id, business id, role, name, email, `source` = `STANDALONE` or `INTEGRATION`). Two ways to build it: the cookie filter
  (standalone) and the Bearer-token filter (`IntegrationTokenVerifier`, embedded). Controllers and services only see `CurrentUser`.
- The tenant (`business_id`) is set from `CurrentUser` for each DB connection (row-level security), identically in both modes.
- The mode is a configuration switch (`juristrack.integration.enabled`), not separate code paths in business logic.

**Frontend (`juris-track-frontend`)**
- `src/track/` holds all JurisTrack pages, routes (`trackRoutes`), actions, types and rules. It does not own login or the shell and imports nothing from
  `src/standalone`. Everything environment-specific comes through an injected `TrackContext` (API base path, extra headers such as `Authorization`, current user,
  translate function, navigate).
- `src/standalone/` holds the shell, sidebar, login, session (`GET /api/v1/me`), theme, toasts and language switcher, and supplies the `TrackContext` (`/api/v1`, cookie session).
- Embedding into the JurisOne UI (later): the JurisOne frontend imports `src/track` (as a package or a federated module) and supplies its own `TrackContext` whose
  base path is `/api/v1/juris-track` (the gateway) and whose headers are empty (JurisOne adds the token server-side). The JurisOne shell uses the same design CSS
  (both apps use the People prototype CSS), so the pages look native.

## 5. Decisions and open questions

Decided by the owner:
1. A manager sees **direct reports by default**; a switch adds one more level (indirect reports).
2. **Managers can correct their reports' time** in embedded and standalone mode, but **never their own**; reason mandatory, audited.
3. "Individual" mode = a company that bought only JurisTrack (standalone).
4. **Teams are dropped**; the reporting line (`manager_id`) replaces them. Testing will be done on fresh data.
5. Embedded: one role, the **JurisTrack manager**, granted in JurisOne; whoever has it sees everyone under them in the reporting line.

Still open (defaults in bold; change anything later without redesign):
- Gateway path: **`/api/v1/juris-track/**` on the JurisOne backend** (extends the existing placeholder `GET /api/v1/juris-track`).
- How a standalone company gets created: **the `SUPER_ADMIN` creates the business and its owner in the app; the first super admin comes from the `seed` command
  (`juristrack.seed.super-admin-email`); self-sign-up later**.
- Key distribution: **one key pair, public key in a property/env var; JWKS later**. Embedded API deployment: **internal-only network**.
- Plan/subscription gating: **JurisOne decides (module `JURIS_TRACK`)**; a standalone company's subscription is a later topic.
- Can the owner (`ADMIN`) track their own time in standalone? **Yes** (same personal section); nobody corrects their own time.
