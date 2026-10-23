# Owner's instructions

This file is for the project owner to write requirements and working rules in. It is loaded automatically at the start of
every Claude Code session (through `CLAUDE.md`), so whatever is written here is followed without being repeated.
Add new lines at the bottom of the right section. Hindi, Hinglish or English are all fine. If two lines disagree, the newer one wins.

## How to work with me (git and PRs)

- Create every pull request against the `dev` branch first, never `main`. New feature branches are cut from `dev`.
- Never merge a pull request yourself. Open it, share the link and stop.
- Commit and push only when asked. Do not push on your own.
- Never put `.idea/` (or other IDE folders) in a commit; they are git-ignored.
- Commit messages end with the Claude co-author line given by the harness.

## Communication

- Reply in Hindi/Hinglish (the owner writes in Hindi). Keep answers short: what changed, what was checked, what is left.
- Say plainly when something was not tested or could not be done. Do not claim a check that was not run.
- Ask before deleting things that cannot be recovered (files outside git, local data). Explain what and why.

## Project structure

- Two repositories: this one is the **backend** (`juris-track`: Spring Boot API in `backend/`, Postgres, jobs) and `juris-track-frontend` is the **web app** (React + Vite). Frontend code lives only in the frontend repo; backend code only here.
- The frontend repo's browser tests (`scripts/e2e-env.mjs` there) build and start this API from a checkout next to it; keep `./mvnw -DskipTests package` and the `migrate` / `seed` / `reset` commands working.
- JurisTrack runs **standalone** (its own database, own emailed-code sign-in) and **embedded in JurisOne** (JurisOne's login; its backend is the gateway and sends a short-lived signed JWT in the `Authorization` header; users are provisioned just in time). Design: `references/INTEGRATION.md`. Nothing in the JurisOne repos is changed until the owner says so.
- The old Next.js + Prisma app is in this repo's git history (`origin/feature/juristrack-time-tracking`, last commit `679d9e5`) and in `C:\JurisLPO\JurisTrack`. It is the reference for behaviour only; do not copy its code style.

## Code and configuration rules

- The repo must stay clean: no unused files, no dead code, no leftover references to removed things (Next.js, Prisma, `.env`).
- Configuration is one file per environment: `backend/src/main/resources/application.properties` and `application-dev|sqe|uat|prd.properties`. Do not add `.env` files or separate templates. Secrets of sqe/uat/prd are `${ENV_VARS}` from the server and are never committed.
- Config files live under `src/` (resources), not in the repository root. JurisTrack stores no uploaded or generated files.
- The backend is a pure Java project: no Node, no `package.json`.
- Verify before saying "done": backend `./mvnw test` (in `backend/`); frontend `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`, and `npm run test:e2e` for anything the UI or API contract touches.

## Naming and REST standards (mandatory for all new code; fix old code when you touch it)

**Names (both repos)**
- Names say what a thing is, in full words: `AttendancePolicy`, not `Pol`; `departmentId`, not `dept`; `month`, not `ym`. No cryptic abbreviations in class, type, function, field, variable, file, route, query-parameter or JSON names.
- Single-letter names only for a loop index (`i`, `j`), an exception or event (`e`), the translate function `t`, and a tiny one-expression lambda. Never for domain values.
- Booleans read as a question (`isActive`, `hasPhoto`, `canEdit`). Functions are verbs (`loadEmployees`, `confirmEmployee`); classes, records and types are nouns.
- Casing: classes, records, enums, React components and TypeScript types are `PascalCase`; methods, variables, fields, JSON fields and query parameters are `camelCase`; constants are `UPPER_SNAKE_CASE`; URL path segments are `kebab-case`; database tables and columns are `snake_case`.
- Java packages are lowercase and follow the layers already used (`controller`, `service`, `repository`, `entity`, `engine`, `security`). A file holds the one class it is named after.

**REST API (backend, and the frontend calls that use it)**
- A URL names a resource, never an action: plural nouns for collections (`/api/leave/requests`), the id in the path (`/api/people/{id}`), sub-resources for what belongs to it (`/api/people/{id}/photo`). No verbs in paths. An action that has no resource of its own is modelled as creating one (`POST /api/auth/otp-requests`, `POST /api/policies/{id}/acknowledgements`), or as setting a state (`PUT /api/people/{id}/status`).
- Methods: `GET` reads and never changes anything; `POST` creates (answer `201` with the new id); `PUT` sets or replaces and is idempotent; `PATCH` changes part of a resource; `DELETE` removes. Do not tunnel changes through `GET`.
- Collections take their filters, sorting and paging as camelCase query parameters (`status`, `departmentId`, `month`, `search`, `sortBy`, `page`), always descriptive. The current user's own collection is `/mine` everywhere.
- Status codes mean what they say: `200`, `201`, `204`, `400` bad input, `401` not signed in, `402` subscription, `403` not allowed, `404` not found, `409` conflict, `422` a business rule refused it, `429` too many requests. Errors use the one error shape the API already has (RFC 7807 problem details); never invent another.
- A new endpoint is added together with its integration test, its entry in `references/API.md` (the endpoint contract) and, where it changes a rule, `references/MIGRATION_PLAN.md` / `references/INTEGRATION.md`, and (for a write) an audit entry.
- Screens that need several things at once (`/api/home`, `/api/overview`, `/api/team`, `/api/shell`) are view models, not resources. Keep them few; do not add more where a resource will do.
- Do not rename or remove an endpoint, parameter or JSON field without changing every caller (the other repo, the tests) in the same change.

**Backend specifics**
- Controllers only map HTTP to a service call and shape the response; rules live in `service` and `engine`. A request or response body is a `record` named after what it carries (`EmployeeInput`, `PayrollRow`), never `Dto1` or an abbreviation.
- Never edit a migration that has been applied; add a new `V<n>__<what_it_does>.sql` with its `U<n>` rollback.

## Documents: FRD and flow diagrams (versioned)

- Each product has a **Functional Requirements Document (FRD)** and a **flow diagram**, and the two together have one **connection flow diagram** (how JurisTrack and JurisOne talk to each other: sign-in token, gateway, roster, roles, audit). Files, in the backend repos' `references/` folder: `FRD-JurisOne.md`, `FLOW-JurisOne.md`, `FRD-JurisTrack.md`, `FLOW-JurisTrack.md`, `FLOW-JurisTrack-JurisOne-Connection.md`. The master copy of the connection diagram is in `juris-track-backend/references/`; the `Juris-one` backend keeps a copy with the same version.
- Diagrams are Mermaid inside the `.md` (they render on GitHub and in the IDE) and must be readable at a glance: who (role / system) does what, in order, with the decisions and the error paths. A new reader should understand how the flow works from the diagram alone.
- Every document starts with a **version table** (version, date, what changed, who asked). Whenever a requirement or a flow changes (new or changed endpoint, role, rule, screen flow, integration step), update the affected FRD and diagram **in the same pull request** as the code and raise the document version. Small change = minor (1.1 -> 1.2); a new area or a changed architecture = major (1.x -> 2.0). A pull request that changes behaviour without the document update is not finished.
- Document version is not the app version; do not mix them. Keep the old versions in the version table, not as extra files.
- When JurisOne changes something that crosses over to JurisTrack (the integration), update the connection diagram in both repos with the same version number.

## Requirements (add yours here)

- **Night shifts (US / UK shifts worked from India):** a workday may start on one calendar date and end on the next. Every calculation (productive and break time, break count, last break, averages, the live board and its tiles, history, reports and CSV, corrections, the "Not ended" flag, the daily chart) must work on the workday's own intervals, never on calendar midnight. The workday belongs to the date it started (in the business time zone); its end may be on the next date and the screens then show the end with its date. Working across midnight must not change any total, and a person who is still working after midnight is "Working", not "Not started".
- **Shift-based calculation (reference: Time Doctor):** time is calculated per shift, not per calendar day. Typical shifts, in India time: US shift 19:30 to 04:30, UK shift 14:00 to 23:00 (9 hours each); some people work different times, so a shift is defined by the owner and assigned per employee (the default shift is the calendar day, 00:00 to 24:00, which is how the app behaved before). A workday belongs to one shift occurrence and carries that shift's date; a 19:30 to 04:30 shift gives exactly the productive time worked in it (about 9 hours), never a part on one calendar day and a part on the next, and work of the next or the previous shift is never added on to it. "Today" on the timer, the dashboard and the live board means the current shift occurrence. Reports, history and CSV group by shift date and show the shift.

## Decisions already taken

- **Audit:** a complete, append-only audit of every movement (time events, people and settings changes, sign-in and security events, access to other people's data, exports). Details in `references/MIGRATION_PLAN.md` (audit policy). Every new write endpoint ships with its audit entry, a test, and a line in `AuditEndpointCoverageTest`; security events are aggregated per 15-minute window.

- Sign-in is by emailed code only (fixed code `000000` only in dev). No passwords. When embedded in JurisOne the sign-in is JurisOne's and JurisTrack trusts the signed token.
- JurisTrack is sold standalone and also embedded in JurisOne; keep one codebase and one set of endpoints for both. The backend builds one `CurrentUser` from either the cookie session or the integration token; the frontend keeps all pages in `src/track` (embeddable, no login or shell) apart from `src/standalone`.
- Purpose: a manager sees, for every employee under them, who works or is on break, how long, breaks and productive time. Roles: `SUPER_ADMIN` (platform, no business) creates businesses and their owner `ADMIN`; the owner adds employees and assigns each a manager (`manager_id`; the reporting line replaces teams); `MANAGER` = the JurisTrack manager; `EMPLOYEE` = everybody else. No HR/Finance roles. Everyone, managers included, has the same personal tracking section; a manager also gets a Team tab. A manager sees direct reports by default, with a switch for indirect reports (one more level), and can correct their reports' time (reason mandatory, audited) but never their own. Embedded in JurisOne: the JurisTrack manager role is granted in JurisOne and sent as `trackRole=MANAGER` on every token; every employee can use JurisTrack whatever their JurisOne role; JurisOne pushes the roster (employees and their managers). "Individual" mode = a company that bought only JurisTrack (standalone).
- Multi-business like JurisOne (`business_id` + row-level security, `TenantContext`). Users are either JurisTrack's own (standalone) or JurisOne employees arriving through the integration token (provisioned just in time, matched by `jurisone_employee_id`, then email). There is no database import from JurisOne.
- Starting work again after End Day reopens today's workday (the gap counts as neither productive nor break).
- Fresh database, no data is migrated from the old Next.js app.
- English and Hindi (English text is the translation key in the frontend).
- The UI uses the JurisOne design CSS (verbatim prototype CSS plus app additions), not Tailwind.
- The identity provider (IAM JCG / Sita) is not to be worked on until the owner says so.
- Ports (dev): web 3007, API 8082, DB 5437; e2e web 3008, API 8083.
- The FRD and flow diagrams are written right after the current pull requests are raised (this round of testing, commit and push comes first). When IAM JCG / Sita is connected later, update the FRDs and all three flow diagrams and release a new document version then.
