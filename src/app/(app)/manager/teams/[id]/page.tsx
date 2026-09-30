import { TeamDetailView } from "@/components/views/team-views";
import { requireRole } from "@/lib/auth/guards";

export default async function ManagerTeamPage({ params }: { params: Promise<{ id: string }> }) {
  const actor = await requireRole("MANAGER");
  const { id } = await params;
  return <TeamDetailView actor={actor} teamId={id} back={{ href: "/manager/teams", label: "My Teams" }} />;
}
