"use server";

import { redirect } from "next/navigation";
import { db } from "@/lib/db";
import { createSession, destroySession } from "@/lib/auth/session";
import { verifyPassword } from "@/lib/auth/password";
import { homeFor } from "@/lib/auth/roles";
import { LoginSchema, formToObject } from "@/lib/validation/schemas";

export interface LoginState {
  error?: string;
  email?: string;
}

// Basic per-email throttle. In-process only; put a shared limiter (e.g. at
// the reverse proxy) in front of multi-instance deployments.
const attempts = new Map<string, { count: number; resetAt: number }>();
const MAX_ATTEMPTS = 8;
const WINDOW_MS = 15 * 60 * 1000;

function throttled(key: string): boolean {
  const now = Date.now();
  const entry = attempts.get(key);
  if (!entry || entry.resetAt < now) return false;
  return entry.count >= MAX_ATTEMPTS;
}

function recordFailure(key: string) {
  const now = Date.now();
  const entry = attempts.get(key);
  if (!entry || entry.resetAt < now) attempts.set(key, { count: 1, resetAt: now + WINDOW_MS });
  else entry.count++;
}

export async function loginAction(_prev: LoginState, form: FormData): Promise<LoginState> {
  const parsed = LoginSchema.safeParse(formToObject(form));
  if (!parsed.success) return { error: "Enter your email address and password.", email: String(form.get("email") ?? "") };
  const { email, password } = parsed.data;

  if (throttled(email)) return { error: "Too many sign-in attempts. Please wait a few minutes and try again.", email };

  const user = await db.user.findUnique({
    where: { email },
    select: { id: true, passwordHash: true, isActive: true, role: true },
  });
  const valid = await verifyPassword(password, user?.passwordHash);
  if (!user || !valid) {
    recordFailure(email);
    return { error: "The email or password is incorrect.", email };
  }
  if (!user.isActive) return { error: "This account has been deactivated. Contact your administrator.", email };

  attempts.delete(email);
  await createSession(user.id);
  await db.user.update({ where: { id: user.id }, data: { lastLoginAt: new Date() } });
  redirect(homeFor(user.role));
}

export async function logoutAction(): Promise<void> {
  await destroySession();
  redirect("/login");
}
