import { EmployeeAdminPanel } from "@/components/admin/employee-forms";
import { EmployeeDetailView } from "@/components/views/employee-detail-view";
import { requireRole } from "@/lib/auth/guards";
import { orNotFound } from "@/lib/not-found";
import { getEmployee } from "@/server/services/employees.service";
import { listTeams } from "@/server/services/teams.service";

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function AdminEmployeePage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: SearchParams }) {
  const actor = await requireRole("ADMIN");
  const { id } = await params;
  const [employee, teams] = await Promise.all([orNotFound(getEmployee(actor, id)), listTeams(actor)]);

  return (
    <EmployeeDetailView
      actor={actor}
      employeeId={id}
      searchParams={await searchParams}
      back={{ href: "/admin/employees", label: "Employees" }}
      adminPanel={
        <EmployeeAdminPanel
          isSelf={employee.id === actor.id}
          teams={teams.map((t) => ({ value: t.id, label: t.name }))}
          employee={{
            id: employee.id,
            firstName: employee.firstName,
            lastName: employee.lastName,
            email: employee.email,
            employeeCode: employee.employeeCode,
            role: employee.role,
            isActive: employee.isActive,
            teamId: employee.team?.id ?? null,
          }}
        />
      }
    />
  );
}
