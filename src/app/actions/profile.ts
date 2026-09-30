"use server";

import { runAction } from "@/lib/action";
import { authorize } from "@/lib/auth/guards";
import { revokeUserSessions } from "@/lib/auth/session";
import { ChangePasswordSchema, formToObject } from "@/lib/validation/schemas";
import { changeOwnPassword } from "@/server/services/employees.service";

export async function changePasswordAction(form: FormData) {
  return runAction(async () => {
    const user = await authorize();
    const input = ChangePasswordSchema.parse(formToObject(form));
    await changeOwnPassword(user.id, input.currentPassword, input.newPassword);
    // Sign out other devices; keep this one.
    await revokeUserSessions(user.id, true);
    return null;
  }, "Password updated. Other devices have been signed out.");
}
