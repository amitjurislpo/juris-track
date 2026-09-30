import Link from "next/link";
import { FilterBar, ParamSearch, ParamSelect } from "@/components/filters/url-filters";
import { Badge, StatusBadge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Avatar, EmptyState } from "@/components/ui/misc";
import { Pagination, Table, Td, Th, THead, Tr } from "@/components/ui/table";
import type { Role } from "@/generated/prisma/enums";
import type { SessionUser } from "@/lib/auth/session";
import { employeeDetailHref, ROLE_LABELS } from "@/lib/auth/roles";
import { formatDuration } from "@/lib/time/format";
import { idParam, oneOf, pageParam, stringParam } from "@/lib/validation/filters";
import { getBoard } from "@/server/services/board.service";
import { listEmployees } from "@/server/services/employees.service";
import { listTeams } from "@/server/services/teams.service";

type Params = Record<string, string | string[] | undefined>;
const ROLES = ["", "EMPLOYEE", "MANAGER", "ADMIN"] as const;

export async function EmployeeDirectory({ actor, searchParams, basePath }: { actor: SessionUser; searchParams: Params; basePath: string }) {
  const q = stringParam(searchParams.q);
  const teamId = searchParams.teamId === "none" ? "none" : idParam(searchParams.teamId);
  const status = oneOf(searchParams.status, ["active", "inactive", "all"] as const, "active");
  const role = oneOf(searchParams.role, ROLES, "") || undefined;
  const page = pageParam(searchParams.page);

  const [list, teams, board] = await Promise.all([
    listEmployees(actor, { q, teamId, status, role: role as Role | undefined, page }),
    listTeams(actor, { includeInactive: false }),
    getBoard(actor),
  ]);
  const live = new Map(board.rows.map((r) => [r.employee.id, r]));

  const hrefFor = (p: number) => {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(searchParams)) if (typeof v === "string" && k !== "page") qs.set(k, v);
    qs.set("page", String(p));
    return `${basePath}?${qs.toString()}`;
  };

  return (
    <Card>
      <div className="border-b border-border px-5 py-4">
        <FilterBar>
          <ParamSearch name="q" label="Search" value={q} placeholder="Name, email or employee ID" />
          <ParamSelect
            name="teamId"
            label="Team"
            value={teamId}
            allLabel="All teams"
            options={[...teams.map((t) => ({ value: t.id, label: t.name })), ...(actor.role === "ADMIN" ? [{ value: "none", label: "Unassigned" }] : [])]}
          />
          {actor.role === "ADMIN" && (
            <ParamSelect
              name="role"
              label="Role"
              value={role}
              allLabel="All roles"
              options={(["EMPLOYEE", "MANAGER", "ADMIN"] as const).map((r) => ({ value: r, label: ROLE_LABELS[r] }))}
            />
          )}
          <ParamSelect
            name="status"
            label="Account"
            value={status}
            options={[
              { value: "active", label: "Active" },
              { value: "inactive", label: "Inactive" },
              { value: "all", label: "All" },
            ]}
          />
        </FilterBar>
      </div>
      {list.rows.length === 0 ? (
        <EmptyState icon="users" title="No employees found" description="Try a different search or filter." />
      ) : (
        <>
          <Table>
            <THead>
              <tr>
                <Th>Employee</Th>
                <Th>Employee ID</Th>
                <Th>Team</Th>
                <Th>Role</Th>
                <Th>Today</Th>
                <Th align="right">Productive today</Th>
                <Th>Account</Th>
              </tr>
            </THead>
            <tbody>
              {list.rows.map((u) => {
                const row = live.get(u.id);
                return (
                  <Tr key={u.id}>
                    <Td>
                      <Link href={employeeDetailHref(actor.role, u.id)} className="flex items-center gap-3 font-medium hover:underline">
                        <Avatar name={u} />
                        <span className="min-w-0">
                          <span className="block truncate">
                            {u.firstName} {u.lastName}
                            {u.isDemo && <Badge tone="accent" className="ml-2 align-middle">Demo</Badge>}
                          </span>
                          <span className="block truncate text-xs font-normal text-ink-3">{u.email}</span>
                        </span>
                      </Link>
                    </Td>
                    <Td className="tabular text-ink-2">{u.employeeCode ?? "—"}</Td>
                    <Td className="text-ink-2">{u.team?.name ?? <span className="text-ink-3">Unassigned</span>}</Td>
                    <Td className="text-ink-2">{ROLE_LABELS[u.role]}</Td>
                    <Td>{row ? <StatusBadge status={row.status} /> : <span className="text-ink-3">—</span>}</Td>
                    <Td align="right">{row?.workday ? formatDuration(row.workday.productiveSeconds) : "—"}</Td>
                    <Td>{u.isActive ? <Badge tone="success">Active</Badge> : <Badge tone="danger">Inactive</Badge>}</Td>
                  </Tr>
                );
              })}
            </tbody>
          </Table>
          <Pagination page={list.page} pageSize={list.pageSize} total={list.total} hrefFor={hrefFor} />
        </>
      )}
    </Card>
  );
}
