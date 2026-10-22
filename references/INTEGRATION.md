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
| API caller | the browser, through the web app's `/api` proxy | the JurisOne backend (gateway) |
| Users and business | created and managed in JurisTrack (admin, or sign-up of the company) | created just in time from the JurisOne token |
| Database | JurisTrack's own (`juristrack`) | the same JurisTrack database/service, one row set per business |

Both modes use the same endpoints, the same permission rules and the same tables. Only *how the caller is authenticated* differs.

## 2. Embedded: how a JurisOne user reaches JurisTrack (service-to-service token)

```
Browser (JurisOne UI, JurisOne cookies)
   |  GET/POST /api/juris-track/**             (same origin as JurisOne, extends the existing placeholder path)
   v
JurisOne backend  = gateway (BFF)
   |  1. checks the JurisOne session, the business plan/grant (module JURIS_TRACK) and the user's JurisOne permission
   |  2. signs a short-lived JWT for this call
   |  3. forwards the request:  Authorization: Bearer <jwt>   (+ X-Request-Id, original method/path/query/body)
   v
JurisTrack API  /api/**  (internal network only, never exposed to browsers in this mode)
   |  verifies the JWT, provisions the business/user if new, applies its own rules, answers
```

Why a gateway and not the browser calling JurisTrack with a token: one origin (cookies, the Origin/CSRF check and CORS stay as today), the signing key never
leaves the JurisOne backend, JurisOne keeps control of who may use the module (plan/grant, suspension), and JurisTrack needs no knowledge of JurisOne sessions.

**Token (JWT, asymmetric):** signed by JurisOne with a private key (ES256 or RS256), verified by JurisTrack with the public key only (configured as
`juristrack.integration.public-key`, later a JWKS URL so keys can rotate). Lifetime about 5 minutes, issued per request or cached for a few minutes.

| Claim | Meaning |
|---|---|
| `iss` | `jurisone` (must equal `juristrack.integration.issuer`) |
| `aud` | `juristrack` (must equal `juristrack.integration.audience`) |
| `sub` | JurisOne employee id (stable) |
| `email`, `name` | for provisioning and display |
| `businessId`, `businessName` | JurisOne business id and name |
| `managerId` (optional) | JurisOne employee id of this employee's assigned manager (null if none): JurisTrack keeps it as `manager_id` and refreshes it on every token |
| `trackRole` (optional) | `MANAGER` when JurisOne has granted this user the **JurisTrack manager** role, otherwise `EMPLOYEE` (optional `ADMIN` only if JurisOne wants an owner-type user). Sent on **every** token; JurisTrack follows it, so granting or removing the role in JurisOne takes effect on the next call |
| `exp`, `iat`, `jti` | expiry (short), issue time, unique id (replay guard) |

**Just-in-time provisioning (implemented):** on a valid token, in system mode inside one transaction: find the business by `businesses.jurisone_business_id` (create it
with the JurisOne business name and defaults if new, refresh the name otherwise), then the user by `users.jurisone_employee_id`; if not found, match by email (this
links a user who first used JurisTrack standalone), else create the user. **The token is authoritative on every call** for name, email, role (`MANAGER` or
`EMPLOYEE`; `ADMIN` only when sent; a downgrade takes effect on the next call) and manager (`manager_id` is resolved by the manager's `jurisone_employee_id` in
the same business; null when the manager is not known yet; never the user themself). Granting or removing the JurisTrack manager role in JurisOne therefore needs
no action in JurisTrack. An unchanged user writes no audit entry. Rules: if the token's JurisOne business is not known yet and the email belongs to a standalone
user whose business is not linked, that business is adopted (it gets the `jurisone_business_id`); an email that belongs to a user of another linked business, or to
the `SUPER_ADMIN`, is refused with 403; a deactivated user is refused with 403; a user of a **suspended business** is refused with 403 `BUSINESS_SUSPENDED` (the super admin suspends businesses); any invalid token answers 401 without details and is never logged.

**Roster sync (part of the first version):** a manager must see all their reports, including those who never opened JurisTrack, so JurisOne also pushes the
roster. `PUT /api/integration/employees` (service token: `aud=juristrack`, `scope=employees:sync`, issued by JurisOne) takes the list of a business's employees
{ id, code, name, email, managerId, isActive } and creates/updates/deactivates users accordingly (idempotent, whole roster or a batch of changes). JurisOne calls
it when employees or manager assignments change and on a schedule. Just-in-time provisioning from the user token still works as a fallback.

**Security rules:** accept the Bearer token only when `juristrack.integration.enabled=true`; reject unsigned, expired or wrong `iss`/`aud` tokens; allow a small
clock skew (`juristrack.integration.clock-skew-seconds`); in embedded deployments the JurisTrack API port is reachable only from the JurisOne backend (network
rule or mTLS); never log tokens; optionally refuse a `jti` that was already used within its lifetime.

## 3. Standalone: how a direct user signs in

Same flow as JurisOne's own sign-in: `POST /api/auth/otp-requests` (email), then `POST /api/auth/sessions` (email + code); cookies `jt_at` / `jt_rt`; fixed code
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

**UI structure for every user:** a personal **section** (Dashboard, My time, My history, Profile) that is always there, even for a manager; a manager additionally
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
- `src/standalone/` holds the shell, sidebar, login, session (`GET /api/me`), theme, toasts and language switcher, and supplies the `TrackContext` (`/api`, cookie session).
- Embedding into the JurisOne UI (later): the JurisOne frontend imports `src/track` (as a package or a federated module) and supplies its own `TrackContext` whose
  base path is `/api/juris-track` (the gateway) and whose headers are empty (JurisOne adds the token server-side). The JurisOne shell uses the same design CSS
  (both apps use the People prototype CSS), so the pages look native.

## 5. Decisions and open questions

Decided by the owner:
1. A manager sees **direct reports by default**; a switch adds one more level (indirect reports).
2. **Managers can correct their reports' time** in embedded and standalone mode, but **never their own**; reason mandatory, audited.
3. "Individual" mode = a company that bought only JurisTrack (standalone).
4. **Teams are dropped**; the reporting line (`manager_id`) replaces them. Testing will be done on fresh data.
5. Embedded: one role, the **JurisTrack manager**, granted in JurisOne; whoever has it sees everyone under them in the reporting line.

Still open (defaults in bold; change anything later without redesign):
- Gateway path: **`/api/juris-track/**` on the JurisOne backend** (extends the existing placeholder `GET /api/juris-track`).
- How a standalone company gets created: **the `SUPER_ADMIN` creates the business and its owner in the app; the first super admin comes from the `seed` command
  (`juristrack.seed.super-admin-email`); self-sign-up later**.
- Key distribution: **one key pair, public key in a property/env var; JWKS later**. Embedded API deployment: **internal-only network**.
- Plan/subscription gating: **JurisOne decides (module `JURIS_TRACK`)**; a standalone company's subscription is a later topic.
- Can the owner (`ADMIN`) track their own time in standalone? **Yes** (same personal section); nobody corrects their own time.
