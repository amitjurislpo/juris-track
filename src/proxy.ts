import { NextResponse, type NextRequest } from "next/server";

/**
 * Optimistic edge check: requests without a session cookie are sent to
 * /login (pages) or rejected (API). This is only a fast path — every page,
 * server action and route handler still validates the session and role
 * against the database.
 */

const SESSION_COOKIES = ["jt_session", "__Host-jt_session"];
const PUBLIC_PATHS = ["/login"];

export function proxy(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const hasSession = SESSION_COOKIES.some((c) => req.cookies.has(c));
  const isPublic = PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`));

  if (!hasSession && !isPublic) {
    if (pathname.startsWith("/api/")) {
      return NextResponse.json({ ok: false, code: "UNAUTHENTICATED", error: "Authentication required." }, { status: 401 });
    }
    const url = req.nextUrl.clone();
    url.pathname = "/login";
    url.search = "";
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|icon.svg|robots.txt).*)"],
};
