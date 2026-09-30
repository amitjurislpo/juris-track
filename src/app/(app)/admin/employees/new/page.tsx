import type { Metadata } from "next";
import { CreateEmployeeForm } from "@/components/admin/employee-forms";
import { PageHeader } from "@/components/ui/misc";
import { requireRole } from "@/lib/auth/guards";
import { idParam } from "@/lib/validation/filters";
import { listTeams } from "@/server/services/teams.service";

export const metadata: Metadata = { title: "Add Employee" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function NewEmployeePage({ searchParams }: { searchParams: SearchParams }) {
  const actor = await requireRole("ADMIN");
  const teams = await listTeams(actor);
  const teamId = idParam((await searchParams).teamId);
  const defaultTeamId = teams.some((t) => t.id === teamId) ? teamId : undefined;

  return (
    <div className="max-w-3xl">
      <PageHeader
        back={defaultTeamId ? { href: `/admin/teams/${defaultTeamId}`, label: "Back to team" } : { href: "/admin/employees", label: "Employees" }}
        title="Add employee"
        description="Create an account. Sign-in credentials are generated securely and never displayed in employee tables."
      />
      <CreateEmployeeForm teams={teams.map((t) => ({ value: t.id, label: t.name }))} defaultTeamId={defaultTeamId} />
    </div>
  );
}
