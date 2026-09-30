import { redirect } from "next/navigation";
import type { Role } from "@/generated/prisma/enums";
import { AppError } from "@/lib/errors";
import { getSessionUser, type SessionUser } from "./session";
import { homeFor } from "./roles";

/** Page guard: redirects anonymous users to /login. */
export async function requireUser(): Promise<SessionUser> {
  const user = await getSessionUser();
  if (!user) redirect("/login");
  return user;
}

/** Page guard: redirects users without one of the roles to their own home. */
export async function requireRole(...roles: Role[]): Promise<SessionUser> {
  const user = await requireUser();
  if (!roles.includes(user.role)) redirect(homeFor(user.role));
  return user;
}

/** Action / API guard: throws a typed error instead of redirecting. */
export async function authorize(...roles: Role[]): Promise<SessionUser> {
  const user = await getSessionUser();
  if (!user) throw new AppError("UNAUTHENTICATED", "Your session has expired. Please sign in again.");
  if (roles.length > 0 && !roles.includes(user.role)) {
    throw new AppError("FORBIDDEN", "You do not have permission to perform this action.");
  }
  return user;
}
