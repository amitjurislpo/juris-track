import "dotenv/config";
import { defineConfig, env } from "prisma/config";

export default defineConfig({
  schema: "prisma/schema.prisma",
  migrations: {
    path: "prisma/migrations",
    // `prisma db seed` / `migrate reset` only create the administrator.
    seed: "tsx prisma/bootstrap.ts",
  },
  datasource: {
    url: env("DATABASE_URL"),
  },
});
