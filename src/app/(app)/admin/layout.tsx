import { requireRole } from "@/lib/auth/guards";

/**
 * Section guard. It runs outside this section's loading boundary, so an
 * unauthorized request gets a real server-side redirect before anything
 * streams. Pages and actions still check again (defense in depth).
 */
export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  await requireRole("ADMIN");
  return children;
}
