import { NextResponse } from "next/server";
import { HTTP_STATUS, toFailure } from "@/lib/errors";

/** Consistent JSON envelope + status mapping for route handlers. */
export async function jsonRoute<T>(fn: () => Promise<T>): Promise<NextResponse> {
  try {
    const data = await fn();
    return NextResponse.json({ ok: true, data }, { headers: { "Cache-Control": "no-store" } });
  } catch (e) {
    const failure = toFailure(e);
    return NextResponse.json(failure, { status: HTTP_STATUS[failure.code], headers: { "Cache-Control": "no-store" } });
  }
}
