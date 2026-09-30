import type { Metadata } from "next";
import { PageHeader } from "@/components/ui/misc";
import { TeamsGrid } from "@/components/views/team-views";
import { requireRole } from "@/lib/auth/guards";

export const metadata: Metadata = { title: "My Teams" };

export default async function ManagerTeamsPage() {
  const actor = await requireRole("MANAGER");
  return (
    <>
      <PageHeader title="My Teams" description="Teams you are responsible for, with today's status." />
      <TeamsGrid actor={actor} />
    </>
  );
}
