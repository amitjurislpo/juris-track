"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { runAction } from "@/lib/action";
import { authorize } from "@/lib/auth/guards";
import { AppError } from "@/lib/errors";
import { getSettings } from "@/server/services/settings.service";
import { DISPLAY_TZ_COOKIE } from "@/server/viewer-timezone";

/** Saves the viewer's display timezone (per browser). */
export async function setDisplayTimezoneAction(timezone: string) {
  return runAction(async () => {
    await authorize();
    const { displayTimezones } = await getSettings();
    if (typeof timezone !== "string" || !displayTimezones.includes(timezone)) {
      throw new AppError("VALIDATION", "That timezone is not available.");
    }
    (await cookies()).set(DISPLAY_TZ_COOKIE, timezone, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      path: "/",
      maxAge: 365 * 24 * 60 * 60,
    });
    revalidatePath("/", "layout");
    return { timezone };
  });
}
