import type { NextRequest } from "next/server";
import { jsonRoute } from "@/lib/api";
import { authorize } from "@/lib/auth/guards";
import { dateParam, idParam, statusParam } from "@/lib/validation/filters";
import { getBoard } from "@/server/services/board.service";

export const dynamic = "force-dynamic";

/** Live status board for managers (their teams) and admins (everyone). */
export async function GET(req: NextRequest) {
  return jsonRoute(async () => {
    const actor = await authorize("ADMIN", "MANAGER");
    const p = req.nextUrl.searchParams;
    return getBoard(actor, {
      date: dateParam(p.get("date")),
      teamId: idParam(p.get("teamId")),
      employeeId: idParam(p.get("employeeId")),
      status: statusParam(p.get("status")),
    });
  });
}
