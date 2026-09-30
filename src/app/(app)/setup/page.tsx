import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { SettingsForm } from "@/components/admin/settings-form";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { PageHeader } from "@/components/ui/misc";
import { requireRole } from "@/lib/auth/guards";
import { timezoneOptions } from "@/lib/time/zones";
import { getSettings, setupDefaults } from "@/server/services/settings.service";

export const metadata: Metadata = { title: "Create organization" };

/** First-run setup: the administrator creates the organization. */
export default async function SetupPage() {
  await requireRole("ADMIN");
  if ((await getSettings()).configured) redirect("/admin");
  const values = setupDefaults();
  const timezones = timezoneOptions([values.timezone, ...values.displayTimezones]);

  return (
    <div className="max-w-2xl">
      <PageHeader
        title="Create your organization"
        description="Step 1 of 3 — create the organization. Next you'll add teams, then create users and share their sign-in details."
      />
      <Card>
        <CardHeader title="Organization details" description="You can change any of this later in Settings." />
        <CardBody>
          <SettingsForm mode="create" values={values} timezones={timezones} />
        </CardBody>
      </Card>
    </div>
  );
}
