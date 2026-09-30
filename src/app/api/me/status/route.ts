import { jsonRoute } from "@/lib/api";
import { authorize } from "@/lib/auth/guards";
import { getEmployeeState } from "@/server/services/time-tracking.service";

export const dynamic = "force-dynamic";

/** Current time state of the signed-in user (polled by the timer panel). */
export async function GET() {
  return jsonRoute(async () => {
    const user = await authorize("EMPLOYEE", "MANAGER");
    return getEmployeeState(user.id);
  });
}
