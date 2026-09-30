import type { Metadata } from "next";
import { LiveBoard } from "@/components/board/live-board";
import { PageHeader } from "@/components/ui/misc";
import { requireRole } from "@/lib/auth/guards";
import { formatDateKey } from "@/lib/time/format";
import { dateParam, idParam, statusParam } from "@/lib/validation/filters";
import { getBoard } from "@/server/services/board.service";
import { scopedEmployeeOptions } from "@/server/services/employees.service";
import { listTeams } from "@/server/services/teams.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const metadata: Metadata = { title: "Manager Dashboard" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function ManagerDashboard({ searchParams }: { searchParams: SearchParams }) {
  const actor = await requireRole("MANAGER");
  const sp = await searchParams;
  const filters = { date: dateParam(sp.date), teamId: idParam(sp.teamId), employeeId: idParam(sp.employeeId), status: statusParam(sp.status) };
  const [board, teams, employees, viewTz] = await Promise.all([
    getBoard(actor, filters),
    listTeams(actor),
    scopedEmployeeOptions(actor),
    getViewerTimezone(),
  ]);

  return (
    <>
      <PageHeader title="Team overview" description={`${formatDateKey(board.today)} · live status of employees in your teams`} />
      <LiveBoard
        initial={board}
        initialFilters={filters}
        teams={teams.map((t) => ({ value: t.id, label: t.name }))}
        employees={employees}
        detailBase="/manager/employees"
        displayTimezone={viewTz}
      />
    </>
  );
}
