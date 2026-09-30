"use server";

import { runAction } from "@/lib/action";
import { authorize } from "@/lib/auth/guards";
import { AppError } from "@/lib/errors";
import type { TimeCommand } from "@/lib/time/types";
import { TimeCommandSchema } from "@/lib/validation/schemas";
import { applyTimeCommand } from "@/server/services/time-tracking.service";

const MESSAGES: Record<TimeCommand, string> = {
  start: "Workday started. Have a productive day.",
  break: "Break started. Your productive timer is paused.",
  resume: "Welcome back. Your productive timer is running.",
  end: "Workday ended. Your summary is ready.",
};

/**
 * Start / break / resume / end for the signed-in user only. The employee id
 * is taken from the session — the client cannot target another employee.
 */
export async function timeCommandAction(command: TimeCommand) {
  return runAction(async () => {
    const user = await authorize("EMPLOYEE", "MANAGER");
    const parsed = TimeCommandSchema.safeParse(command);
    if (!parsed.success) throw new AppError("VALIDATION", "Unknown action.");
    return applyTimeCommand(user.id, parsed.data);
  }, MESSAGES[command] ?? undefined);
}
