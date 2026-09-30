import { jsonRoute } from "@/lib/api";
import { authorize } from "@/lib/auth/guards";
import { idParam } from "@/lib/validation/filters";
import { AppError } from "@/lib/errors";
import { assertCanViewEmployee } from "@/server/services/scope.service";
import { getEmployeeState } from "@/server/services/time-tracking.service";

export const dynamic = "force-dynamic";

/** Live state of one employee, for managers (in scope) and admins. */
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  return jsonRoute(async () => {
    const actor = await authorize("ADMIN", "MANAGER");
    const id = idParam((await ctx.params).id);
    if (!id) throw new AppError("NOT_FOUND", "Employee not found.");
    await assertCanViewEmployee(actor, id);
    return getEmployeeState(id);
  });
}
