# JurisTrack API: reports and CSV export

The contract of the report endpoints (module 5.5). Everything in `API.md` "Conventions" applies (base path, authentication, success body
`{ ok, message?, data? }`, problem details with `code`, list parameters, types). Keep this file exactly in sync with `ReportController` /
`ReportService`.

| Method | Path | Roles | Purpose |
|---|---|---|---|
| GET | `/api/reports/workdays` | ADMIN, MANAGER | The workdays of the people in scope for a date range: rows, totals, daily series. With `Accept: text/csv`: the same report as a CSV file |
| GET | `/api/reports/summaries` | ADMIN, MANAGER | The same workdays added up per employee, per manager of the day, or per date |

EMPLOYEE and SUPER_ADMIN get 403 `FORBIDDEN`; signed out 401. A suspended business answers 403 `BUSINESS_SUSPENDED` as everywhere.

## Who sees whom (`scope`)

| Caller | `scope` | Default | Sees |
|---|---|---|---|
| MANAGER | `direct` | yes | their direct reports (`users.manager_id` = the manager) |
| MANAGER | `indirect` | | also the reports of their direct reports (one level further down) |
| MANAGER | `all` | | 403 `FORBIDDEN` ("You can see only the people who report to you.") |
| ADMIN (owner) | `all` | yes | everybody in the business, the owner included |
| ADMIN (owner) | `direct` / `indirect` | | the owner's own reporting line, as for a manager |

Any case is accepted (`DIRECT`). A manager is never in their own scope and never sees anybody above or beside them. Deactivated people stay in
scope (their history stays visible). Another business's data is never visible (row-level security plus an explicit business filter).

## Shared query parameters (both endpoints)

| Parameter | Values | Default |
|---|---|---|
| `range` | `today`, `yesterday`, `week` (Monday to today), `month` (the 1st to today), `custom` | `week` (as the old reports page) |
| `from`, `to` | `YYYY-MM-DD`, with `range=custom`: a missing `from` is this week's Monday, a missing `to` today, reversed ends are swapped, at most 366 days (an earlier `from` is moved up, the end is kept) | |
| `scope` | `direct`, `indirect`, `all` (see above) | by role |
| `employeeId` | one person (uuid); must be in scope, else 404 `NOT_FOUND` "Employee not found." (also unknown ids and other businesses' people) | |
| `managerId` | only workdays whose **manager of the day** (the workday's `manager_id` snapshot) is this user. A manager may give themselves or someone in scope; the owner with `scope=all` anyone of the business; else 404 "Manager not found." | |
| `status` | one or more of `WORKING`, `ON_BREAK`, `DAY_ENDED`, `NOT_ENDED`, comma-separated, any case. `NOT_ENDED` = an open day longer than the business's `staleWorkdayHours` (such a day also matches `WORKING` / `ON_BREAK`) | every status |
| `search` | text (cut to 100 characters) matched against the employee's first name, last name, "first last", email and employee code (`%` `_` literal) | |
| `sort`, `sortDirection`, `page`, `pageSize` | list parameters of `API.md`; the sort keys are per endpoint (below) | |

"Today" is the date in the business time zone. A workday belongs to the date it **started** on in the business time zone (a night shift from
22:00 to 06:00 is on the first date). 400 `VALIDATION_FAILED` with `errors[].field` for every bad parameter at once: `range`, `from`, `to`,
`scope`, `employeeId`, `managerId`, `status`, `groupBy`, `sort`, `sortDirection`, `page`, `pageSize` (and `timeZone` for the CSV).
Order of checks: 400, then 403 (`scope=all` for a manager), then 404 (`employeeId`, `managerId`); the CSV's `timeZone` (400) and row cap
(422) are checked after these.

## Figures

An ended workday shows the totals stored when it ended (`spanSeconds` = end - start). An open workday is computed from its work and break
intervals up to the request time (`endedAt` null, `productiveSeconds` / `breakSeconds` / `spanSeconds` running). Durations are whole seconds.
Every figure covers the whole range (all pages), whatever `page` / `pageSize` say.

`totals = { workdays, employees, productiveSeconds, breakSeconds, spanSeconds, breakCount, averageProductiveSeconds }`
(`employees` = different people; `averageProductiveSeconds` = productive seconds per workday, rounded half up, 0 without workdays).

`dailySeries = [{ date, productiveSeconds, breakSeconds, workdays }]`: one point for every date from `from` to `to` (zero when nothing was
recorded), for the chart. The series and the totals always add up to the rows.

## GET /api/reports/workdays

Sort keys: `date` (default), `employee` (name, case-insensitive), `startedAt`, `productiveSeconds`, `breakSeconds`, `spanSeconds`,
`breakCount`. **Default `sortDirection` is `desc`** (newest first). Ties: date (newest first), employee name, start, id.

`data = { rows, total, page, pageSize, range, from, to, scope, totals, dailySeries }` (`range` = the preset used, `scope` = the scope used).

Row:
```json
{ "id": "uuid", "date": "2026-10-20",
  "employee": { "id": "uuid", "employeeCode": "E1" | null, "name": "Esha Employee" },
  "manager": { "id": "uuid", "name": "Maya Manager" } | null,
  "status": "WORKING" | "ON_BREAK" | "DAY_ENDED", "isStale": false,
  "startedAt": "2026-10-20T03:30:00Z", "endedAt": "2026-10-20T09:30:00Z" | null,
  "productiveSeconds": 21600, "breakSeconds": 0, "spanSeconds": 21600, "breakCount": 0 }
```
`manager` is the employee's manager **when the workday started** (snapshot), shown with that person's current name; null when the employee had
no manager that day. `isStale` true = "Not ended" (open and longer than `staleWorkdayHours`).

### CSV: `GET /api/reports/workdays` with `Accept: text/csv`

The same resource in another representation (no verb, no extra path): when the `Accept` header names `text/csv` (any quality), the answer is
the CSV file; otherwise (no header, `*/*`, `application/json`) it is the JSON above. A browser download needs `fetch` with that header and a
blob (a plain link cannot set `Accept`). Same parameters, scope and checks as the JSON; **every row of the range** in the requested order
(`page` / `pageSize` are ignored). One more parameter:

| Parameter | Values | Default |
|---|---|---|
| `timeZone` | the business time zone or one of its `displayTimeZones` (IANA id, any case); anything else, also an unknown zone, is 400 with field `timeZone` | the business time zone |

Answer 200, `Content-Type: text/csv;charset=UTF-8`, `Content-Disposition: attachment; filename="juristrack-<from>-to-<to>.csv"`,
`Cache-Control: no-store`. Errors stay `application/problem+json` (also with `Accept: text/csv`).

File (byte for byte as the old app's export):
- UTF-8 with a byte order mark (`EF BB BF`), lines ending in CRLF, the last line included.
- Header: `Date,Employee,Employee ID,Manager,Status,Start (<abbr>),End (<abbr>),Productive hours,Break hours,Span hours,Breaks`;
  `<abbr>` = the display zone's abbreviation **at the moment of the export** (as the old app): `IST`, `EDT` in summer / `EST` in winter, `GMT`,
  `BST`...
- Row: date (`YYYY-MM-DD`), "First Last", employee code (empty without), manager of the day (empty without), status (`Working`, `On Break`,
  `Day Ended`; `Not ended` for a stale open day; the old app showed `Working` there), start and end as `HH:mm` (24 h) in the display zone (end
  empty while open), hours with two decimals (`8.50`, rounded like JavaScript's `toFixed(2)`), number of breaks. Text is English (the CSV is
  not translated).
- Every cell (header included) goes through the old `csvCell`: a cell starting with `=`, `+`, `-`, `@`, tab or carriage return gets a leading
  `'` (spreadsheet formula injection guard); then a cell containing `"`, `,`, LF or CR is put in double quotes with its quotes doubled.
  Example: the name `=cmd |calc, "x"` becomes `"'=cmd |calc, ""x"""`.
- Row cap: more than `juristrack.reports.maximum-export-rows` rows (default 50000) is refused with 422 `EXPORT_TOO_LARGE` ("This export has N
  rows; at most M fit in one file. Choose a shorter date range or fewer people."); nothing is exported or audited. The JSON report has no cap.

Zone abbreviations come from `engine/ZoneNames` (a port of the old `zoneAbbr`: the English CLDR abbreviation where one exists, else the
initials of the long name, so Singapore is `SST` and Kathmandu `NT` as in the old app). Checked against the golden cases and against Node 24 /
ICU 78.3 for every zone; the differences left come from the different CLDR versions of the JDK and Node and are listed in `ZoneNames`
(none for India, the main American, European, Gulf, South/East Asian or Australian zones).

## GET /api/reports/summaries

`groupBy` = `employee` (default) | `manager` (the manager of the day; workdays without one form the group with key `none`) | `date`.
Sort keys: `label` (default; case-insensitive; the `none` group is last in both directions), `workdays`, `employees`, `productiveSeconds`,
`breakSeconds`, `spanSeconds`, `breakCount`, `averageProductiveSeconds`. Default `sortDirection`: `asc`, but `desc` (newest first) for
`groupBy=date`. Ties: label, key. Paged like any list (`total` = groups).

`data = { rows, total, page, pageSize, groupBy, range, from, to, scope, totals, dailySeries }`; `totals` and `dailySeries` as above (the
groups add up to `totals`).

Row:
```json
{ "key": "uuid" | "none" | "2026-10-20", "label": "Esha Employee" | "Maya Manager" | null | "2026-10-20",
  "employeeCode": "E1" | null, "workdays": 3, "employees": 1, "productiveSeconds": 57600, "breakSeconds": 3600,
  "spanSeconds": 61200, "breakCount": 1, "averageProductiveSeconds": 19200,
  "firstStartedAt": "2026-10-19T03:30:00Z", "lastEndedAt": "2026-10-20T09:30:00Z" | null }
```
`key` = the employee's id / the manager's id (`none` without a manager) / the date; `label` = the name / the date (null for `none`: the client
shows "No manager"); `employeeCode` only for `groupBy=employee`; `employees` = different people in the group; `firstStartedAt` = earliest start,
`lastEndedAt` = latest end of the group's ended days (null when all are open).

## Audit

Every successful report request writes **one** audit entry (never one per row), in the request's transaction, through `ReportAccessAudit`
(the only place the reports touch the audit log):

| Request | `action` | `entity_type` / `entity_id` | `subject_id` | `after` |
|---|---|---|---|---|
| JSON workdays | `reports.viewed` | `report` / `workdays` | the `employeeId` filter, else null | `{ category: "access", report, filters, rowCount }` |
| summaries | `reports.viewed` | `report` / `summaries` | as above | as above, `filters.groupBy` set, `rowCount` = groups |
| CSV | `reports.exported` | `report` / `workdays` | as above | `{ category: "export", report, filters, rowCount, timeZone, fileName }` |

`filters = { range, from, to, scope, employeeId, managerId, status: [...], search, sort, sortDirection }` (resolved values). Refused requests
(400, 403, 404, 422) write nothing here.

## Database

`V11__workdays_business_date_index.sql` (rollback `U11`): index `workdays (business_id, work_date)` for the owner's whole-business range query.
Every request is set-based: one query for the scope (the business's reporting line), one for the range's workdays with the employee and the
manager joined, one each for the work and break intervals of the open days; no query per row.
