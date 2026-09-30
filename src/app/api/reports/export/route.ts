import { NextResponse, type NextRequest } from "next/server";
import { authorize } from "@/lib/auth/guards";
import { HTTP_STATUS, toFailure } from "@/lib/errors";
import { formatTime, toDecimalHours } from "@/lib/time/format";
import { resolveRange } from "@/lib/time/ranges";
import { idParam } from "@/lib/validation/filters";
import { getWorkdayRecords } from "@/server/services/reports.service";
import { orgToday } from "@/server/services/settings.service";
import { LIVE_STATUS_LABELS } from "@/lib/time/types";
import { zoneAbbr } from "@/lib/time/zones";
import { getViewerTimezone } from "@/server/viewer-timezone";

export const dynamic = "force-dynamic";

function csvCell(value: string | number): string {
  const s = String(value);
  // Neutralize spreadsheet formula injection and quote when needed.
  const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

/** CSV export of workday records — a renderer over the same report rows as the UI. */
export async function GET(req: NextRequest) {
  try {
    const actor = await authorize("ADMIN", "MANAGER");
    const p = req.nextUrl.searchParams;
    const { today } = await orgToday();
    const timezone = await getViewerTimezone();
    const abbr = zoneAbbr(timezone);
    const range = resolveRange({ range: p.get("range") ?? undefined, from: p.get("from") ?? undefined, to: p.get("to") ?? undefined }, today, "week");
    const records = await getWorkdayRecords(actor, {
      from: range.from,
      to: range.to,
      teamId: idParam(p.get("teamId")),
      employeeId: idParam(p.get("employeeId")),
    });

    const header = ["Date", "Employee", "Employee ID", "Team", "Status", `Start (${abbr})`, `End (${abbr})`, "Productive hours", "Break hours", "Span hours", "Breaks"];
    const lines = [header.map(csvCell).join(",")];
    for (const r of records) {
      lines.push(
        [
          r.date,
          `${r.employee.firstName} ${r.employee.lastName}`,
          r.employee.employeeCode ?? "",
          r.team?.name ?? "",
          LIVE_STATUS_LABELS[r.status],
          formatTime(r.startedAt, timezone),
          r.endedAt ? formatTime(r.endedAt, timezone) : "",
          toDecimalHours(r.productiveSeconds),
          toDecimalHours(r.breakSeconds),
          toDecimalHours(r.spanSeconds),
          r.breakCount,
        ]
          .map(csvCell)
          .join(","),
      );
    }
    return new NextResponse(`﻿${lines.join("\r\n")}\r\n`, {
      headers: {
        "Content-Type": "text/csv; charset=utf-8",
        "Content-Disposition": `attachment; filename="juristrack-${range.from}-to-${range.to}.csv"`,
        "Cache-Control": "no-store",
      },
    });
  } catch (e) {
    const failure = toFailure(e);
    return NextResponse.json(failure, { status: HTTP_STATUS[failure.code] });
  }
}
