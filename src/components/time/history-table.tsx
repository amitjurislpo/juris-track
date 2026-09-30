import Link from "next/link";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/misc";
import { Table, Td, Th, THead, Tr } from "@/components/ui/table";
import { formatDateKey, formatDuration, formatTime } from "@/lib/time/format";
import { zoneAbbr } from "@/lib/time/zones";
import type { WorkdayRecord } from "@/server/services/reports.service";

/** Daily workday records. Totals are derived from the recorded intervals. */
export function HistoryTable({
  records,
  timezone,
  showEmployee = false,
  employeeHref,
  emptyTitle = "No workdays in this period",
}: {
  records: WorkdayRecord[];
  timezone: string;
  showEmployee?: boolean;
  employeeHref?: (id: string) => string;
  emptyTitle?: string;
}) {
  if (records.length === 0) {
    return <EmptyState icon="calendar" title={emptyTitle} description="Workdays will appear here once time is recorded." />;
  }
  return (
    <Table>
      <THead>
        <tr>
          <Th>Date</Th>
          {showEmployee && <Th>Employee</Th>}
          {showEmployee && <Th>Team</Th>}
          <Th>Status</Th>
          <Th>Start ({zoneAbbr(timezone)})</Th>
          <Th>End</Th>
          <Th align="right">Productive</Th>
          <Th align="right">Break</Th>
          <Th align="right">Span</Th>
          <Th align="right">Breaks</Th>
        </tr>
      </THead>
      <tbody>
        {records.map((r) => (
          <Tr key={r.workdayId}>
            <Td className="whitespace-nowrap">{formatDateKey(r.date)}</Td>
            {showEmployee && (
              <Td className="whitespace-nowrap">
                {employeeHref ? (
                  <Link href={employeeHref(r.employee.id)} className="font-medium hover:underline">
                    {r.employee.firstName} {r.employee.lastName}
                  </Link>
                ) : (
                  `${r.employee.firstName} ${r.employee.lastName}`
                )}
              </Td>
            )}
            {showEmployee && <Td className="text-ink-2">{r.team?.name ?? "—"}</Td>}
            <Td>
              <span className="flex items-center gap-2">
                <StatusBadge status={r.status} />
                {r.isStale && <Badge tone="warning">Not ended</Badge>}
              </span>
            </Td>
            <Td className="tabular">{formatTime(r.startedAt, timezone)}</Td>
            <Td className="tabular">{r.endedAt ? formatTime(r.endedAt, timezone) : "—"}</Td>
            <Td align="right" className="font-semibold">
              {formatDuration(r.productiveSeconds)}
            </Td>
            <Td align="right">{formatDuration(r.breakSeconds)}</Td>
            <Td align="right">{formatDuration(r.spanSeconds)}</Td>
            <Td align="right">{r.breakCount}</Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}
