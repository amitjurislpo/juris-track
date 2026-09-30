/**
 * Production bootstrap: ensures the initial administrator exists.
 *
 *   npm run db:bootstrap     create the admin from ADMIN_EMAIL / ADMIN_PASSWORD
 *                            if missing (safe to re-run; never deletes data)
 *   npm run db:reset-data    DELETE ALL DATA, then create only the admin
 *
 * The organization itself is created by the administrator in the app
 * (first sign-in opens the organization setup page), followed by teams and
 * users.
 */
import "dotenv/config";
import { db } from "../src/lib/db";
import { hashPassword } from "../src/lib/auth/password";

const wipe = process.argv.includes("--wipe");

async function main() {
  const email = process.env.ADMIN_EMAIL?.trim().toLowerCase();
  const password = process.env.ADMIN_PASSWORD;
  if (!email || !password) {
    console.error("Set ADMIN_EMAIL and ADMIN_PASSWORD in .env first.");
    process.exit(1);
  }

  if (wipe) {
    if (process.env.NODE_ENV === "production" && process.env.ALLOW_DATA_WIPE !== "true") {
      console.error("Refusing to wipe data in production. Set ALLOW_DATA_WIPE=true to override.");
      process.exit(1);
    }
    // Dependency order. This removes every record, including the organization.
    await db.auditLog.deleteMany();
    await db.workSession.deleteMany();
    await db.breakSession.deleteMany();
    await db.workday.deleteMany();
    await db.teamMembership.deleteMany();
    await db.session.deleteMany();
    await db.team.deleteMany();
    await db.user.deleteMany();
    await db.organizationSetting.deleteMany();
    console.log("All data deleted.");
  }

  const existing = await db.user.findUnique({ where: { email }, select: { id: true, role: true } });
  if (existing) {
    console.log(`Administrator ${email} already exists — nothing to do.`);
    return;
  }

  await db.user.create({
    data: {
      firstName: "System",
      lastName: "Administrator",
      email,
      role: "ADMIN",
      passwordHash: await hashPassword(password),
    },
  });
  console.log(`Created administrator ${email}. Sign in to create the organization.`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => db.$disconnect());
