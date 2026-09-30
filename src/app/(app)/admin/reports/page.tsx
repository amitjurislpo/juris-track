import type { Metadata } from "next";
import { ReportsView } from "@/components/views/reports-view";
import { requireRole } from "@/lib/auth/guards";

export const metadata: Metadata = { title: "Reports" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function AdminReportsPage({ searchParams }: { searchParams: SearchParams }) {
  const actor = await requireRole("ADMIN");
  return <ReportsView actor={actor} searchParams={await searchParams} title="Reports" />;
}
