import { EmployeeDetailView } from "@/components/views/employee-detail-view";
import { requireRole } from "@/lib/auth/guards";

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function ManagerEmployeePage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: SearchParams }) {
  const actor = await requireRole("MANAGER");
  const { id } = await params;
  return <EmployeeDetailView actor={actor} employeeId={id} searchParams={await searchParams} back={{ href: "/manager/employees", label: "Employees" }} />;
}
