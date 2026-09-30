import Link from "next/link";
import type { ReactNode } from "react";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { Card, CardHeader } from "@/components/ui/card";
import { Avatar, EmptyState, PageHeader, StatCard } from "@/components/ui/misc";
import { Table, Td, Th, THead, Tr } from "@/components/ui/table";
import type { SessionUser } from "@/lib/auth/session";
import { employeeDetailHref, teamDetailHref, ROLE_LABELS } from "@/lib/auth/roles";
import { orNotFound } from "@/lib/not-found";
import { formatDuration, formatTime } from "@/lib/time/format";
import { getBoard, metricsByTeam, emptyMetrics } from "@/server/services/board.service";
import { getTeam, listTeams } from "@/server/services/teams.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

/** Team cards with today's live counts (server-rendered snapshot). */
export async function TeamsGrid({ actor, includeInactive = false, emptyAction }: { actor: SessionUser; includeInactive?: boolean; emptyAction?: ReactNode }) {
  const [teams, board] = await Promise.all([listTeams(actor, { includeInactive }), getBoard(actor)]);
  const byTeam = metricsByTeam(board.rows);

  if (teams.length === 0) {
    return (
      <Card>
        <EmptyState
          icon="team"
          title={actor.role === "ADMIN" ? "No teams yet" : "You don't manage any teams yet"}
          description={actor.role === "ADMIN" ? "Create your first team, then add employees to it." : "An administrator can assign you as the manager of a team."}
          action={emptyAction}
        />
      </Card>
    );
  }

  return (
    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      {teams.map((t) => {
        const m = byTeam.get(t.id) ?? emptyMetrics();
        return (
          <Link key={t.id} href={teamDetailHref(actor.role, t.id)} className="group">
            <Card className="h-full transition-shadow group-hover:shadow-md">
              <div className="flex items-start justify-between gap-3 px-5 pt-5">
                <div className="min-w-0">
                  <h3 className="truncate text-base font-semibold text-ink group-hover:underline">{t.name}</h3>
                  <p className="mt-0.5 truncate text-sm text-ink-3">
                    {t.manager ? `Manager: ${t.manager.firstName} ${t.manager.lastName}` : "No manager assigned"}
                  </p>
                </div>
                {t.isActive ? <Badge>{t.memberCount} members</Badge> : <Badge tone="danger">Inactive</Badge>}
              </div>
              <div className="mt-4 grid grid-cols-4 gap-px border-t border-border bg-border text-center">
                {[
                  { label: "Working", value: m.working, dot: "bg-st-working" },
                  { label: "Break", value: m.onBreak, dot: "bg-st-break" },
                  { label: "Not started", value: m.notStarted, dot: "bg-st-idle/60" },
                  { label: "Ended", value: m.dayEnded, dot: "bg-st-ended" },
                ].map((s) => (
                  <div key={s.label} className="bg-surface px-2 py-3">
                    <p className="tabular text-lg font-semibold text-ink">{s.value}</p>
                    <p className="flex items-center justify-center gap-1 text-[11px] text-ink-3">
                      <span className={`size-1.5 rounded-full ${s.dot}`} aria-hidden="true" />
                      {s.label}
                    </p>
                  </div>
                ))}
              </div>
              <div className="flex justify-between border-t border-border px-5 py-3 text-sm">
                <span className="text-ink-3">Today</span>
                <span className="tabular text-ink-2">
                  <strong className="font-semibold text-ink">{formatDuration(m.productiveSeconds)}</strong> productive ·{" "}
                  {formatDuration(m.breakSeconds)} break
                </span>
              </div>
            </Card>
          </Link>
        );
      })}
    </div>
  );
}

/** Team detail: members with current status and today's hours. */
export async function TeamDetailView({
  actor,
  teamId,
  back,
  adminPanel,
  memberActions,
}: {
  actor: SessionUser;
  teamId: string;
  back: { href: string; label: string };
  adminPanel?: ReactNode;
  memberActions?: (member: { id: string; name: string }) => ReactNode;
}) {
  const team = await orNotFound(getTeam(actor, teamId));
  const [board, viewTz] = await Promise.all([getBoard(actor, { teamId: team.id }), getViewerTimezone()]);
  const byEmployee = new Map(board.rows.map((r) => [r.employee.id, r]));
  const m = board.metrics;

  return (
    <>
      <PageHeader
        back={back}
        title={
          <span className="flex flex-wrap items-center gap-3">
            {team.name}
            {!team.isActive && <Badge tone="danger">Inactive</Badge>}
          </span>
        }
        description={team.description ?? undefined}
      />
      <div className="mb-6 grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
        <StatCard label="Manager" value={<span className="text-base">{team.manager ? `${team.manager.firstName} ${team.manager.lastName}` : "—"}</span>} />
        <StatCard label="Employees" value={team.memberships.length} />
        <StatCard label="Working" value={m.working} tone="working" hint={`${m.onBreak} on break`} />
        <StatCard label="Not started" value={m.notStarted} tone="idle" hint={`${m.dayEnded} day ended`} />
        <StatCard label="Productive today" value={formatDuration(m.productiveSeconds)} />
        <StatCard label="Break today" value={formatDuration(m.breakSeconds)} />
      </div>

      {adminPanel && <div className="mb-6">{adminPanel}</div>}

      <Card>
        <CardHeader title="Members" description={`Status as of ${formatTime(board.serverNow, viewTz)}`} />
        {team.memberships.length === 0 ? (
          <EmptyState icon="users" title="No employees in this team" description={actor.role === "ADMIN" ? "Use Add Employee to assign someone." : undefined} />
        ) : (
          <Table>
            <THead>
              <tr>
                <Th>Employee</Th>
                <Th>Role</Th>
                <Th>Status</Th>
                <Th align="right">Productive</Th>
                <Th align="right">Break</Th>
                <Th>Start</Th>
                <Th>End</Th>
                <Th align="right">Breaks</Th>
                {memberActions && <Th align="right">Actions</Th>}
              </tr>
            </THead>
            <tbody>
              {team.memberships.map(({ employee: e }) => {
                const row = byEmployee.get(e.id);
                const wd = row?.workday;
                return (
                  <Tr key={e.id}>
                    <Td>
                      <Link href={employeeDetailHref(actor.role, e.id)} className="flex items-center gap-3 font-medium hover:underline">
                        <Avatar name={e} />
                        <span>
                          <span className="block">
                            {e.firstName} {e.lastName}
                          </span>
                          <span className="block text-xs font-normal text-ink-3">{e.email}</span>
                        </span>
                      </Link>
                    </Td>
                    <Td className="text-ink-2">{ROLE_LABELS[e.role]}</Td>
                    <Td>{e.isActive ? <StatusBadge status={row?.status ?? "NOT_STARTED"} /> : <Badge tone="danger">Inactive</Badge>}</Td>
                    <Td align="right" className="font-semibold">
                      {wd ? formatDuration(wd.productiveSeconds) : "—"}
                    </Td>
                    <Td align="right">{wd ? formatDuration(wd.breakSeconds) : "—"}</Td>
                    <Td className="tabular">{formatTime(wd?.startedAt, viewTz)}</Td>
                    <Td className="tabular">{formatTime(wd?.endedAt, viewTz)}</Td>
                    <Td align="right">{wd?.breakCount ?? "—"}</Td>
                    {memberActions && <Td align="right">{memberActions({ id: e.id, name: `${e.firstName} ${e.lastName}` })}</Td>}
                  </Tr>
                );
              })}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
