import type { Metadata } from "next";
import { PageHeader } from "@/components/ui/misc";
import { EmployeeDirectory } from "@/components/views/employee-directory";
import { requireRole } from "@/lib/auth/guards";

export const metadata: Metadata = { title: "Employees" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function ManagerEmployeesPage({ searchParams }: { searchParams: SearchParams }) {
  const actor = await requireRole("MANAGER");
  return (
    <>
      <PageHeader title="Employees" description="Employees in the teams you manage." />
      <EmployeeDirectory actor={actor} searchParams={await searchParams} basePath="/manager/employees" />
    </>
  );
}
