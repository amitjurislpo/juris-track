"use server";

import { revalidatePath } from "next/cache";
import { runAction } from "@/lib/action";
import { authorize } from "@/lib/auth/guards";
import {
  AssignTeamSchema,
  EmployeeCreateSchema,
  EmployeeUpdateSchema,
  SetActiveSchema,
  formToObject,
} from "@/lib/validation/schemas";
import {
  assignTeam,
  createEmployee,
  resetPassword,
  setEmployeeActive,
  updateEmployee,
} from "@/server/services/employees.service";

export async function createEmployeeAction(form: FormData) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const input = EmployeeCreateSchema.parse(formToObject(form));
    const { user, temporaryPassword } = await createEmployee(actor, input);
    revalidatePath("/", "layout");
    return { id: user.id, name: `${user.firstName} ${user.lastName}`, email: user.email, temporaryPassword };
  }, "User created.");
}

export async function updateEmployeeAction(form: FormData) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    await updateEmployee(actor, EmployeeUpdateSchema.parse(formToObject(form)));
    revalidatePath("/", "layout");
    return null;
  }, "Employee details saved.");
}

export async function setEmployeeActiveAction(input: { id: string; active: boolean; reason?: string }) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const { id, active, reason } = SetActiveSchema.parse(input);
    await setEmployeeActive(actor, id, active, reason);
    revalidatePath("/", "layout");
    return null;
  }, input.active ? "Employee reactivated." : "Employee deactivated.");
}

export async function assignTeamAction(input: { employeeId: string; teamId?: string }) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const { employeeId, teamId } = AssignTeamSchema.parse(input);
    const changed = await assignTeam(actor, employeeId, teamId ?? null);
    revalidatePath("/", "layout");
    return { changed };
  }, input.teamId ? "Team assignment updated." : "Employee removed from team.");
}

export async function resetPasswordAction(id: string) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const temporaryPassword = await resetPassword(actor, String(id));
    return { temporaryPassword };
  }, "Password reset. Share the temporary password securely.");
}
