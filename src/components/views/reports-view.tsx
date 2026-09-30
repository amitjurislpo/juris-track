import Link from "next/link";
import { FilterBar, ParamSelect, RangeFilter } from "@/components/filters/url-filters";
import { DailyChart } from "@/components/time/daily-chart";
import { HistoryTable } from "@/components/time/history-table";
import { buttonClass } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { Icon } from "@/components/ui/icons";
import { EmptyState, PageHeader, StatCard } from "@/components/ui/misc";
import { Table, Td, Th, THead, Tr } from "@/components/ui/table";
import type { SessionUser } from "@/lib/auth/session";
import { employeeDetailHref, teamDetailHref } from "@/lib/auth/roles";
import { formatDateKey, formatDuration, formatTime } from "@/lib/time/format";
import { resolveRange } from "@/lib/time/ranges";
import { idParam, oneOf } from "@/lib/validation/filters";
import { scopedEmployeeOptions } from "@/server/services/employees.service";
import { aggregate, dailySeries, getWorkdayRecords, totalsOf, type GroupBy } from "@/server/services/reports.service";
import { orgToday } from "@/server/services/settings.service";
import { listTeams } from "@/server/services/teams.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

const GROUPS: readonly GroupBy[] = ["employee", "team", "date", "workday"];
const GROUP_LABELS: Record<GroupBy, string> = {
  employee: "By employee",
  team: "By team",
  date: "By date",
  workday: "Individual workdays",
};

type Params = Record<string, string | string[] | undefined>;

export async function ReportsView({ actor, searchParams, title }: { actor: SessionUser; searchParams: Params; title: string }) {
  const { today } = await orgToday();
  const timezone = await getViewerTimezone();
  const range = resolveRange(searchParams, today, "week");
  const teamId = idParam(searchParams.teamId);
  const employeeId = idParam(searchParams.employeeId);
  const groupBy = oneOf(searchParams.groupBy, GROUPS, "employee");

  const [records, teams, employees] = await Promise.all([
    getWorkdayRecords(actor, { from: range.from, to: range.to, teamId, employeeId }),
    listTeams(actor, { includeInactive: true }),
    scopedEmployeeOptions(actor, { includeInactive: true }),
  ]);
  const totals = totalsOf(records);
  // Start/end times are only meaningful within a single calendar day.
  const showTimes = groupBy === "date" || range.from === range.to;

  const exportQs = new URLSearchParams({ range: range.preset, from: range.from, to: range.to });
  if (teamId) exportQs.set("teamId", teamId);
  if (employeeId) exportQs.set("employeeId", employeeId);

  return (
    <>
      <PageHeader
        title={title}
        description={`${formatDateKey(range.from)} – ${formatDateKey(range.to)} · productive hours are calculated from recorded work intervals`}
        actions={
          <a href={`/api/reports/export?${exportQs.toString()}`} className={buttonClass("secondary")}>
            <Icon name="download" className="size-4" /> Export CSV
          </a>
        }
      />

      <Card className="mb-6">
        <CardBody className="space-y-4">
          <RangeFilter range={range} />
          <FilterBar>
            <ParamSelect name="teamId" label="Team" value={teamId} allLabel="All teams" options={teams.map((t) => ({ value: t.id, label: t.isActive ? t.name : `${t.name} (inactive)` }))} />
            <ParamSelect name="employeeId" label="Employee" value={employeeId} allLabel="All employees" options={employees} />
            <ParamSelect name="groupBy" label="Group" value={groupBy} options={GROUPS.map((g) => ({ value: g, label: GROUP_LABELS[g] }))} />
          </FilterBar>
        </CardBody>
      </Card>

      <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="Workdays" value={totals.workdays} hint={`${totals.employees} employee${totals.employees === 1 ? "" : "s"}`} />
        <StatCard label="Productive" value={formatDuration(totals.productiveSeconds)} tone="working" />
        <StatCard label="Break" value={formatDuration(totals.breakSeconds)} tone="break" />
        <StatCard label="Worked span" value={formatDuration(totals.spanSeconds)} />
        <StatCard label="Breaks taken" value={totals.breakCount} />
      </div>

      {range.from !== range.to && records.length > 0 && (
        <Card className="mb-6">
          <CardHeader title="Productive hours per day" description="Sum across the selected employees" />
          <CardBody>
            <DailyChart points={dailySeries(records, range.from, range.to)} />
          </CardBody>
        </Card>
      )}

      <Card>
        <CardHeader title={GROUP_LABELS[groupBy]} description="Team attribution uses the employee's team on the day the work was recorded." />
        {groupBy === "workday" ? (
          <HistoryTable records={records} timezone={timezone} showEmployee employeeHref={(id) => employeeDetailHref(actor.role, id)} />
        ) : records.length === 0 ? (
          <EmptyState icon="chart" title="No recorded time for these filters" />
        ) : (
          <Table>
            <THead>
              <tr>
                <Th>{groupBy === "employee" ? "Employee" : groupBy === "team" ? "Team" : "Date"}</Th>
                <Th align="right">Workdays</Th>
                <Th align="right">Productive</Th>
                <Th align="right">Avg / day</Th>
                <Th align="right">Break</Th>
                <Th align="right">Span</Th>
                <Th align="right">Breaks</Th>
                {showTimes && <Th>Earliest start</Th>}
                {showTimes && <Th>Latest end</Th>}
              </tr>
            </THead>
            <tbody>
              {aggregate(records, groupBy).map((row) => (
                <Tr key={row.key}>
                  <Td className="font-medium whitespace-nowrap">
                    {row.href ? (
                      <Link
                        className="hover:underline"
                        href={row.href.kind === "employee" ? employeeDetailHref(actor.role, row.href.id) : teamDetailHref(actor.role, row.href.id)}
                      >
                        {row.label}
                      </Link>
                    ) : groupBy === "date" ? (
                      formatDateKey(row.label)
                    ) : (
                      row.label
                    )}
                    {row.sublabel && <span className="ml-2 text-xs font-normal text-ink-3">{row.sublabel}</span>}
                  </Td>
                  <Td align="right">{row.workdays}</Td>
                  <Td align="right" className="font-semibold">
                    {formatDuration(row.productiveSeconds)}
                  </Td>
                  <Td align="right">{formatDuration(row.avgProductiveSeconds)}</Td>
                  <Td align="right">{formatDuration(row.breakSeconds)}</Td>
                  <Td align="right">{formatDuration(row.spanSeconds)}</Td>
                  <Td align="right">{row.breakCount}</Td>
                  {showTimes && <Td className="tabular">{formatTime(row.firstStart, timezone)}</Td>}
                  {showTimes && <Td className="tabular">{formatTime(row.lastEnd, timezone)}</Td>}
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
