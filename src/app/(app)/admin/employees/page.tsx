import type { Metadata } from "next";
import { ButtonLink } from "@/components/ui/button";
import { Icon } from "@/components/ui/icons";
import { PageHeader } from "@/components/ui/misc";
import { EmployeeDirectory } from "@/components/views/employee-directory";
import { requireRole } from "@/lib/auth/guards";

export const metadata: Metadata = { title: "Employees" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function AdminEmployeesPage({ searchParams }: { searchParams: SearchParams }) {
  const actor = await requireRole("ADMIN");
  return (
    <>
      <PageHeader
        title="Employees"
        description="Everyone in the organization. Open a profile to edit, assign a team, or review time history."
        actions={
          <ButtonLink href="/admin/employees/new">
            <Icon name="plus" className="size-4" /> Add Employee
          </ButtonLink>
        }
      />
      <EmployeeDirectory actor={actor} searchParams={await searchParams} basePath="/admin/employees" />
    </>
  );
}
