import { requireRole } from "@/lib/auth/guards";

/** Section guard for personal time tracking — see admin/layout.tsx. */
export default async function TrackerLayout({ children }: { children: React.ReactNode }) {
  await requireRole("EMPLOYEE", "MANAGER");
  return children;
}
