"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { runAction } from "@/lib/action";
import { authorize } from "@/lib/auth/guards";
import { TeamSchema, TeamUpdateSchema, formToObject } from "@/lib/validation/schemas";
import { createTeam, deleteTeam, setTeamActive, updateTeam } from "@/server/services/teams.service";

export async function createTeamAction(form: FormData) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const team = await createTeam(actor, TeamSchema.parse(formToObject(form)));
    revalidatePath("/", "layout");
    return { id: team.id, name: team.name };
  }, "Team created.");
}

export async function updateTeamAction(form: FormData) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const { id, ...input } = TeamUpdateSchema.parse(formToObject(form));
    await updateTeam(actor, id, input);
    revalidatePath("/", "layout");
    return null;
  }, "Team saved.");
}

const ActiveInput = z.object({ id: z.string().min(1).max(64), active: z.boolean(), reason: z.string().max(500).optional() });

export async function setTeamActiveAction(input: { id: string; active: boolean; reason?: string }) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    const { id, active, reason } = ActiveInput.parse(input);
    await setTeamActive(actor, id, active, reason);
    revalidatePath("/", "layout");
    return null;
  }, input.active ? "Team reactivated." : "Team deactivated.");
}

export async function deleteTeamAction(id: string) {
  return runAction(async () => {
    const actor = await authorize("ADMIN");
    await deleteTeam(actor, z.string().min(1).max(64).parse(id));
    revalidatePath("/", "layout");
    return null;
  }, "Team deleted.");
}
