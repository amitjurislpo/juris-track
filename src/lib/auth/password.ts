import bcrypt from "bcryptjs";
import { randomInt } from "node:crypto";

const COST = 12;

// Hash compared against when the user does not exist, so login timing does
// not reveal whether an email is registered. Generated once, lazily.
let dummyHash: Promise<string> | null = null;

export function hashPassword(plain: string): Promise<string> {
  return bcrypt.hash(plain, COST);
}

export async function verifyPassword(plain: string, hash: string | null | undefined): Promise<boolean> {
  if (!hash) {
    dummyHash ??= bcrypt.hash("juristrack-timing-guard", COST);
    await bcrypt.compare(plain, await dummyHash);
    return false;
  }
  return bcrypt.compare(plain, hash);
}

/**
 * Organization default password for newly created users (DEFAULT_USER_PASSWORD).
 * Users are flagged to change it after their first sign-in.
 */
export function defaultUserPassword(): string | null {
  const value = process.env.DEFAULT_USER_PASSWORD?.trim();
  return value ? value : null;
}

const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

/** Readable temporary password, e.g. "Jt-kP7x-Qm4r-Zt9w". */
export function generateTemporaryPassword(): string {
  const chunk = () => Array.from({ length: 4 }, () => ALPHABET[randomInt(ALPHABET.length)]).join("");
  return `Jt-${chunk()}-${chunk()}-${chunk()}`;
}
