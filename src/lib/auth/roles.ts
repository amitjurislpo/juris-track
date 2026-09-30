import type { Role } from "@/generated/prisma/enums";

/**
 * Role/permission model. Client-safe (no server imports) so navigation can
 * use it, but every check here is also enforced server-side in guards and
 * services — hiding UI is never the only protection.
 */

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: "Administrator",
  MANAGER: "Manager",
  EMPLOYEE: "Employee",
};

/** Roles that clock in/out and appear on live boards. */
export const TRACKED_ROLES: readonly Role[] = ["EMPLOYEE", "MANAGER"];

export function tracksTime(role: Role): boolean {
  return TRACKED_ROLES.includes(role);
}

export function homeFor(role: Role): string {
  switch (role) {
    case "ADMIN":
      return "/admin";
    case "MANAGER":
      return "/manager";
    default:
      return "/dashboard";
  }
}

export interface NavItem {
  href: string;
  label: string;
  icon: "home" | "clock" | "history" | "user" | "users" | "team" | "chart" | "settings" | "audit" | "live";
}

export function navFor(role: Role): NavItem[] {
  switch (role) {
    case "ADMIN":
      return [
        { href: "/admin", label: "Dashboard", icon: "home" },
        { href: "/admin/teams", label: "Teams", icon: "team" },
        { href: "/admin/employees", label: "Employees", icon: "users" },
        { href: "/admin/time-tracking", label: "Time Tracking", icon: "live" },
        { href: "/admin/reports", label: "Reports", icon: "chart" },
        { href: "/admin/audit-log", label: "Audit Log", icon: "audit" },
        { href: "/admin/settings", label: "Settings", icon: "settings" },
      ];
    case "MANAGER":
      return [
        { href: "/manager", label: "Dashboard", icon: "home" },
        { href: "/manager/teams", label: "My Teams", icon: "team" },
        { href: "/manager/employees", label: "Employees", icon: "users" },
        { href: "/manager/reports", label: "Time Reports", icon: "chart" },
        { href: "/dashboard", label: "My Time", icon: "clock" },
      ];
    default:
      return [
        { href: "/dashboard", label: "Dashboard", icon: "home" },
        { href: "/my-time", label: "My Time", icon: "clock" },
        { href: "/my-history", label: "My History", icon: "history" },
        { href: "/profile", label: "Profile", icon: "user" },
      ];
  }
}

/** Base path for employee detail links, per viewer role. */
export function employeeDetailHref(viewerRole: Role, employeeId: string): string {
  return viewerRole === "ADMIN" ? `/admin/employees/${employeeId}` : `/manager/employees/${employeeId}`;
}

export function teamDetailHref(viewerRole: Role, teamId: string): string {
  return viewerRole === "ADMIN" ? `/admin/teams/${teamId}` : `/manager/teams/${teamId}`;
}
