import { createHmac, randomBytes } from "node:crypto";
import { cookies, headers } from "next/headers";
import { cache } from "react";
import { db } from "@/lib/db";
import { env } from "@/lib/env";
import type { Role } from "@/generated/prisma/enums";

/**
 * Database-backed sessions.
 *
 * The browser holds a random 256-bit token in an httpOnly, SameSite=Lax
 * cookie. The database stores only HMAC-SHA256(token, AUTH_SECRET), so a
 * leaked database cannot be replayed as cookies. Sessions are revocable
 * (logout, deactivation, password reset) because they live server-side.
 */

export const SESSION_COOKIE = process.env.NODE_ENV === "production" ? "__Host-jt_session" : "jt_session";

/** Refresh the sliding expiry at most this often, to avoid a write per request. */
const TOUCH_INTERVAL_MS = 15 * 60 * 1000;

export interface SessionUser {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  role: Role;
  employeeCode: string | null;
  mustChangePassword: boolean;
}

function hashToken(token: string): string {
  return createHmac("sha256", env().AUTH_SECRET).update(token).digest("hex");
}

function ttlMs() {
  return env().SESSION_TTL_HOURS * 60 * 60 * 1000;
}

/**
 * The cookie outlives the server-side session; the database expiry (which
 * slides with activity) is authoritative, so no cookie refresh is needed.
 */
const COOKIE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;

export async function createSession(userId: string): Promise<void> {
  const token = randomBytes(32).toString("base64url");
  const expiresAt = new Date(Date.now() + ttlMs());
  const userAgent = (await headers()).get("user-agent")?.slice(0, 255) ?? null;
  await db.session.create({ data: { id: hashToken(token), userId, expiresAt, userAgent } });
  (await cookies()).set(SESSION_COOKIE, token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: COOKIE_MAX_AGE_SECONDS,
  });
}

export async function destroySession(): Promise<void> {
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  if (token) await db.session.deleteMany({ where: { id: hashToken(token) } });
  jar.delete(SESSION_COOKIE);
}

/** Revokes every session of a user (deactivation, password reset). */
export async function revokeUserSessions(userId: string, exceptCurrent = false): Promise<void> {
  let keep: string | undefined;
  if (exceptCurrent) {
    const token = (await cookies()).get(SESSION_COOKIE)?.value;
    if (token) keep = hashToken(token);
  }
  await db.session.deleteMany({ where: { userId, ...(keep ? { id: { not: keep } } : {}) } });
}

/**
 * Resolves the authenticated user for the current request (memoized per
 * request). Returns null for missing, expired, or deactivated sessions.
 */
export const getSessionUser = cache(async (): Promise<SessionUser | null> => {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token || token.length > 128) return null;

  const id = hashToken(token);
  const session = await db.session.findUnique({
    where: { id },
    select: {
      expiresAt: true,
      lastSeenAt: true,
      user: {
        select: {
          id: true,
          firstName: true,
          lastName: true,
          email: true,
          role: true,
          employeeCode: true,
          mustChangePassword: true,
          isActive: true,
        },
      },
    },
  });

  const now = Date.now();
  if (!session || session.expiresAt.getTime() <= now || !session.user.isActive) {
    if (session) await db.session.deleteMany({ where: { id } });
    return null;
  }

  if (now - session.lastSeenAt.getTime() > TOUCH_INTERVAL_MS) {
    // Sliding expiry: activity extends the server-side session.
    await db.session.updateMany({
      where: { id },
      data: { lastSeenAt: new Date(now), expiresAt: new Date(now + ttlMs()) },
    });
  }

  const { isActive: _isActive, ...user } = session.user;
  return user;
});
