import { RemoveMemberButton, TeamAdminPanel } from "@/components/admin/team-forms";
import { TeamDetailView } from "@/components/views/team-views";
import { requireRole } from "@/lib/auth/guards";
import { orNotFound } from "@/lib/not-found";
import { listUserOptions } from "@/server/services/employees.service";
import { getTeam } from "@/server/services/teams.service";
import { db } from "@/lib/db";

export default async function AdminTeamPage({ params }: { params: Promise<{ id: string }> }) {
  const actor = await requireRole("ADMIN");
  const { id } = await params;
  const team = await orNotFound(getTeam(actor, id));
  const [managers, users, history] = await Promise.all([
    listUserOptions(["MANAGER"]),
    listUserOptions(["EMPLOYEE", "MANAGER"]),
    db.teamMembership.count({ where: { teamId: id } }),
  ]);
  const memberIds = new Set(team.memberships.map((m) => m.employee.id));

  return (
    <TeamDetailView
      actor={actor}
      teamId={id}
      back={{ href: "/admin/teams", label: "Teams" }}
      adminPanel={
        <TeamAdminPanel
          team={{ id: team.id, name: team.name, description: team.description, managerId: team.managerId, isActive: team.isActive, memberCount: team.memberships.length }}
          managers={managers.map((m) => ({ value: m.id, label: `${m.firstName} ${m.lastName}` }))}
          candidates={users
            .filter((u) => !memberIds.has(u.id))
            .map((u) => ({ value: u.id, label: `${u.firstName} ${u.lastName}`, hint: u.memberships[0]?.team.name ? `currently in ${u.memberships[0].team.name}` : "unassigned" }))}
          canDelete={history === 0 && team._count.workdays === 0}
        />
      }
      memberActions={(m) => <RemoveMemberButton employeeId={m.id} name={m.name} teamName={team.name} />}
    />
  );
}
