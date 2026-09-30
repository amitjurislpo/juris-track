import { AppShell } from "@/components/layout/app-shell";
import { requireUser } from "@/lib/auth/guards";
import { getSettings } from "@/server/services/settings.service";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const dynamic = "force-dynamic";

export default async function AuthenticatedLayout({ children }: { children: React.ReactNode }) {
  const user = await requireUser();
  const [settings, viewerTz] = await Promise.all([getSettings(), getViewerTimezone()]);
  const tz = { zones: settings.displayTimezones, current: viewerTz, serverNow: new Date().toISOString() };
  return (
    <AppShell user={user} orgName={settings.organizationName} tz={tz}>
      {children}
    </AppShell>
  );
}
