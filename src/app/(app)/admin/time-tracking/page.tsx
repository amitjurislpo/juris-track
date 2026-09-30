import type { Metadata } from "next";
import Link from "next/link";
import { CloseWorkdayButton } from "@/components/admin/time-correction-controls";
import { LiveBoard } from "@/components/board/live-board";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState, PageHeader } from "@/components/ui/misc";
import { Table, Td, Th, THead, Tr } from "@/components/ui/table";
import { requireRole } from "@/lib/auth/guards";
import { formatDateKey, formatDateTime, formatDuration } from "@/lib/time/format";
import { dateParam, idParam, statusParam } from "@/lib/validation/filters";
import { getBoard } from "@/server/services/board.service";
import { scopedEmployeeOptions } from "@/server/services/employees.service";
import { listTeams } from "@/server/services/teams.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const metadata: Metadata = { title: "Time Tracking" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function AdminTimeTrackingPage({ searchParams }: { searchParams: SearchParams }) {
  const actor = await requireRole("ADMIN");
  const sp = await searchParams;
  const filters = { date: dateParam(sp.date), teamId: idParam(sp.teamId), employeeId: idParam(sp.employeeId), status: statusParam(sp.status) };
  const [board, openBoard, teams, employees] = await Promise.all([
    getBoard(actor, filters),
    getBoard(actor), // unfiltered: used for the open-workday review list
    listTeams(actor),
    scopedEmployeeOptions(actor),
  ]);
  const viewTz = await getViewerTimezone();
  const flagged = openBoard.rows.filter((r) => r.workday?.isStale);

  return (
    <>
      <PageHeader title="Time Tracking" description={`${formatDateKey(board.today)} · live status across the organization`} />

      <Card className="mb-6">
        <CardHeader
          title="Workdays needing review"
          description="Open workdays that have run longer than the configured threshold (Settings). Closing one is recorded in the audit log."
          actions={flagged.length > 0 ? <Badge tone="warning">{flagged.length} flagged</Badge> : undefined}
        />
        {flagged.length === 0 ? (
          <EmptyState icon="check" title="Nothing to review" description="Every open workday is within normal hours." className="py-8" />
        ) : (
          <Table>
            <THead>
              <tr>
                <Th>Employee</Th>
                <Th>Status</Th>
                <Th>Started</Th>
                <Th align="right">Open for</Th>
                <Th align="right">Productive</Th>
                <Th align="right">Action</Th>
              </tr>
            </THead>
            <tbody>
              {flagged.map((r) => (
                <Tr key={r.employee.id}>
                  <Td>
                    <Link href={`/admin/employees/${r.employee.id}`} className="font-medium hover:underline">
                      {r.employee.firstName} {r.employee.lastName}
                    </Link>
                  </Td>
                  <Td>
                    <StatusBadge status={r.status} />
                  </Td>
                  <Td className="tabular">{formatDateTime(r.workday!.startedAt, viewTz)}</Td>
                  <Td align="right">{formatDuration(r.workday!.spanSeconds)}</Td>
                  <Td align="right">{formatDuration(r.workday!.productiveSeconds)}</Td>
                  <Td align="right">
                    <CloseWorkdayButton workdayId={r.workday!.id} timezone={viewTz} />
                  </Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <LiveBoard
        initial={board}
        initialFilters={filters}
        teams={teams.map((t) => ({ value: t.id, label: t.name }))}
        employees={employees}
        detailBase="/admin/employees"
        displayTimezone={viewTz}
      />
    </>
  );
}
