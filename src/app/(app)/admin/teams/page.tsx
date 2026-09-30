import type { Metadata } from "next";
import { AddTeamButton } from "@/components/admin/team-forms";
import { ParamSelect } from "@/components/filters/url-filters";
import { PageHeader } from "@/components/ui/misc";
import { TeamsGrid } from "@/components/views/team-views";
import { requireRole } from "@/lib/auth/guards";
import { oneOf } from "@/lib/validation/filters";
import { listUserOptions } from "@/server/services/employees.service";

export const metadata: Metadata = { title: "Teams" };

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function AdminTeamsPage({ searchParams }: { searchParams: SearchParams }) {
  const actor = await requireRole("ADMIN");
  const show = oneOf((await searchParams).show, ["active", "all"] as const, "active");
  const managers = (await listUserOptions(["MANAGER"])).map((m) => ({ value: m.id, label: `${m.firstName} ${m.lastName}` }));
  const addButton = <AddTeamButton managers={managers} />;

  return (
    <>
      <PageHeader title="Teams" description="Create and manage teams. Teams are records in the database — add any your organization needs." actions={addButton} />
      <div className="mb-4">
        <ParamSelect
          name="show"
          label="Show"
          value={show}
          options={[
            { value: "active", label: "Active teams" },
            { value: "all", label: "All teams (incl. inactive)" },
          ]}
        />
      </div>
      <TeamsGrid actor={actor} includeInactive={show === "all"} emptyAction={addButton} />
    </>
  );
}
