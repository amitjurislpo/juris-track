import { requireRole } from "@/lib/auth/guards";

/** Section guard — see admin/layout.tsx. */
export default async function ManagerLayout({ children }: { children: React.ReactNode }) {
  await requireRole("MANAGER");
  return children;
}
