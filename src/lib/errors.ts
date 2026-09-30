import { ZodError } from "zod";

export type ErrorCode =
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "VALIDATION"
  | "CONFLICT"
  | "INVALID_STATE"
  | "RATE_LIMITED"
  | "INTERNAL";

export type FieldErrors = Record<string, string[] | undefined>;

/** An expected, user-presentable failure. Anything else is treated as internal. */
export class AppError extends Error {
  constructor(
    public readonly code: ErrorCode,
    message: string,
    public readonly fieldErrors?: FieldErrors,
  ) {
    super(message);
    this.name = "AppError";
  }
}

export const HTTP_STATUS: Record<ErrorCode, number> = {
  UNAUTHENTICATED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  VALIDATION: 422,
  CONFLICT: 409,
  INVALID_STATE: 409,
  RATE_LIMITED: 429,
  INTERNAL: 500,
};

export type ActionResult<T = null> =
  | { ok: true; data: T; message?: string }
  | { ok: false; code: ErrorCode; error: string; fieldErrors?: FieldErrors };

function isUniqueViolation(e: unknown): e is { code: "P2002"; meta?: { target?: unknown } } {
  return typeof e === "object" && e !== null && (e as { code?: unknown }).code === "P2002";
}

/** Normalizes any thrown value into a safe, serializable failure result. */
export function toFailure(e: unknown): Extract<ActionResult<never>, { ok: false }> {
  if (e instanceof AppError) {
    return { ok: false, code: e.code, error: e.message, fieldErrors: e.fieldErrors };
  }
  if (e instanceof ZodError) {
    const fieldErrors: FieldErrors = {};
    for (const issue of e.issues) {
      const key = issue.path.join(".") || "_form";
      (fieldErrors[key] ??= []).push(issue.message);
    }
    return { ok: false, code: "VALIDATION", error: "Please correct the highlighted fields.", fieldErrors };
  }
  if (isUniqueViolation(e)) {
    return { ok: false, code: "CONFLICT", error: "A record with these details already exists." };
  }
  // Log server-side only; never leak internals to the client.
  console.error("[juristrack] unexpected error", e);
  return { ok: false, code: "INTERNAL", error: "Something went wrong. Please try again." };
}
