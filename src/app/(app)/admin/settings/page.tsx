import type { Metadata } from "next";
import { SettingsForm } from "@/components/admin/settings-form";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { DescriptionList, PageHeader } from "@/components/ui/misc";
import { requireRole } from "@/lib/auth/guards";
import { formatDateTime } from "@/lib/time/format";
import { getSettings } from "@/server/services/settings.service";
import { zoneAbbr } from "@/lib/time/zones";

export const metadata: Metadata = { title: "Settings" };

export default async function AdminSettingsPage() {
  await requireRole("ADMIN");
  const settings = await getSettings();
  const timezones = Intl.supportedValuesOf("timeZone");
  if (!timezones.includes(settings.timezone)) timezones.unshift(settings.timezone);

  return (
    <>
      <PageHeader title="Settings" description="Organization-wide configuration." />
      <div className="grid gap-6 xl:grid-cols-[1fr_380px]">
        <Card>
          <CardHeader title="Organization" />
          <CardBody>
            <SettingsForm values={settings} timezones={timezones} />
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Time-tracking policy" />
          <CardBody>
            <DescriptionList
              items={[
                {
                  label: "Current time",
                  value: (
                    <span className="block space-y-0.5">
                      {settings.displayTimezones.map((tz) => (
                        <span key={tz} className="tabular block">
                          {formatDateTime(new Date(), tz)} {zoneAbbr(tz)}
                        </span>
                      ))}
                    </span>
                  ),
                },
                { label: "Workday date", value: "The calendar date (organization timezone) when work started" },
                { label: "Breaks", value: "Must be ended (Back to Work) before the workday can end" },
                { label: "Workdays per day", value: "One per employee per calendar date" },
                { label: "Corrections", value: "Administrators only, with a mandatory reason; all audited" },
              ]}
            />
          </CardBody>
        </Card>
      </div>
    </>
  );
}
