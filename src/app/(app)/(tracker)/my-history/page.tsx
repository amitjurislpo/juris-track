import type { Metadata } from "next";
import { RangeFilter } from "@/components/filters/url-filters";
import { DailyChart } from "@/components/time/daily-chart";
import { HistoryTable } from "@/components/time/history-table";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { PageHeader, StatCard } from "@/components/ui/misc";
import { requireRole } from "@/lib/auth/guards";
import { formatDateKey, formatDuration } from "@/lib/time/format";
import { resolveRange } from "@/lib/time/ranges";
import { dailySeries, getWorkdayRecords, totalsOf } from "@/server/services/reports.service";
import { orgToday } from "@/server/services/settings.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const metadata: Metadata = { title: "My History" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function MyHistoryPage({ searchParams }: { searchParams: SearchParams }) {
  const user = await requireRole("EMPLOYEE", "MANAGER");
  const { today } = await orgToday();
  const timezone = await getViewerTimezone();
  const range = resolveRange(await searchParams, today, "month");
  const records = await getWorkdayRecords(user, { from: range.from, to: range.to, employeeId: user.id });
  const totals = totalsOf(records);
  const days = records.length || 1;

  return (
    <>
      <PageHeader title="My History" description={`${formatDateKey(range.from)} – ${formatDateKey(range.to)}`} />
      <div className="mb-6">
        <RangeFilter range={range} />
      </div>
      <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Workdays" value={records.length} />
        <StatCard label="Productive" value={formatDuration(totals.productiveSeconds)} tone="working" hint={`Avg ${formatDuration(totals.productiveSeconds / days)} / day`} />
        <StatCard label="Break" value={formatDuration(totals.breakSeconds)} tone="break" hint={`${totals.breakCount} breaks`} />
        <StatCard label="Worked span" value={formatDuration(totals.spanSeconds)} />
      </div>
      {range.from !== range.to && (
        <Card className="mb-6">
          <CardHeader title="Daily productive hours" />
          <CardBody>
            <DailyChart points={dailySeries(records, range.from, range.to)} />
          </CardBody>
        </Card>
      )}
      <Card>
        <CardHeader title="Workdays" />
        <HistoryTable records={records} timezone={timezone} />
      </Card>
    </>
  );
}
