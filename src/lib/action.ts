import { unstable_rethrow } from "next/navigation";
import { toFailure, type ActionResult } from "@/lib/errors";

/**
 * Wraps a server action body so every action returns a consistent
 * ActionResult. Framework control flow (redirect/notFound) is re-thrown.
 */
export async function runAction<T>(fn: () => Promise<T>, message?: string): Promise<ActionResult<T>> {
  try {
    const data = await fn();
    return { ok: true, data, message };
  } catch (e) {
    unstable_rethrow(e);
    return toFailure(e);
  }
}
