import type { Metadata } from "next";
import Link from "next/link";
import { DailyChart } from "@/components/time/daily-chart";
import { StatusBadge } from "@/components/ui/badge";
import { ButtonLink } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { Icon } from "@/components/ui/icons";
import { Callout, PageHeader, StatCard } from "@/components/ui/misc";
import { requireRole } from "@/lib/auth/guards";
import { TRACKED_ROLES } from "@/lib/auth/roles";
import { db } from "@/lib/db";
import { formatDateKey, formatDuration } from "@/lib/time/format";
import { addDays, dateKeyToDb } from "@/lib/time/tz";
import { LIVE_STATUSES } from "@/lib/time/types";
import { getBoard } from "@/server/services/board.service";
import { dailySeries, getWorkdayRecords } from "@/server/services/reports.service";

export const metadata: Metadata = { title: "Administration" };

export default async function AdminDashboard() {
  const actor = await requireRole("ADMIN");
  const board = await getBoard(actor);
  const from = addDays(board.today, -13);
  const [totalEmployees, activeEmployees, teams, completedToday, records] = await Promise.all([
    db.user.count({ where: { role: { in: [...TRACKED_ROLES] } } }),
    db.user.count({ where: { role: { in: [...TRACKED_ROLES] }, isActive: true } }),
    db.team.count({ where: { isActive: true } }),
    db.workday.count({ where: { date: dateKeyToDb(board.today), status: "COMPLETED" } }),
    getWorkdayRecords(actor, { from, to: board.today }),
  ]);
  const m = board.metrics;
  const counts = Object.fromEntries(LIVE_STATUSES.map((s) => [s, board.rows.filter((r) => r.status === s).length]));

  return (
    <>
      <PageHeader
        title="Organization overview"
        description={`${formatDateKey(board.today)} · ${board.timezone}`}
        actions={
          <>
            <ButtonLink href="/admin/employees/new" variant="secondary">
              <Icon name="plus" className="size-4" /> Add Employee
            </ButtonLink>
            <ButtonLink href="/admin/time-tracking">
              <Icon name="live" className="size-4" /> Live tracking
            </ButtonLink>
          </>
        }
      />

      {m.stale > 0 && (
        <div className="mb-6">
          <Callout
            tone="warning"
            title={`${m.stale} workday${m.stale === 1 ? " is" : "s are"} still open past the expected end`}
            action={
              <Link href="/admin/time-tracking" className="text-sm font-semibold underline">
                Review
              </Link>
            }
          >
            Employees may have forgotten to end their day. Review and close them with a documented reason.
          </Callout>
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <StatCard label="Total employees" value={totalEmployees} />
        <StatCard label="Active employees" value={activeEmployees} />
        <StatCard label="Teams" value={teams} />
        <StatCard label="Completed workdays" value={completedToday} tone="ended" hint="Today" />
        <StatCard label="Working now" value={m.working} tone="working" />
        <StatCard label="On break now" value={m.onBreak} tone="break" />
        <StatCard label="Productive hours" value={formatDuration(m.productiveSeconds)} hint="Organization, today" />
        <StatCard label="Break hours" value={formatDuration(m.breakSeconds)} hint="Organization, today" />
      </div>

      <div className="mt-6 grid gap-6 xl:grid-cols-[1fr_360px]">
        <Card>
          <CardHeader title="Organization productive hours — last 14 days" />
          <CardBody>
            <DailyChart points={dailySeries(records, from, board.today)} height={220} />
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Right now" description={`${m.total} active employees`} />
          <CardBody className="space-y-3">
            {LIVE_STATUSES.map((s) => (
              <Link key={s} href={`/admin/time-tracking?status=${s}`} className="flex items-center justify-between rounded-lg px-2 py-1.5 hover:bg-surface-2">
                <StatusBadge status={s} />
                <span className="tabular text-lg font-semibold text-ink">{counts[s]}</span>
              </Link>
            ))}
          </CardBody>
        </Card>
      </div>
    </>
  );
}
