import { redirect } from "next/navigation";
import { requireRole } from "@/lib/auth/guards";
import { getSettings } from "@/server/services/settings.service";

/**
 * Section guard. It runs outside this section's loading boundary, so an
 * unauthorized request gets a real server-side redirect before anything
 * streams. Pages and actions still check again (defense in depth).
 * Until the organization exists, administrators are sent to set it up.
 */
export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  await requireRole("ADMIN");
  if (!(await getSettings()).configured) redirect("/setup");
  return children;
}
