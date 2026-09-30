-- Database-level integrity guards that Prisma's schema language cannot express.
-- Prisma does not manage CHECK constraints, so these do not cause drift.

ALTER TABLE "Workday"
  ADD CONSTRAINT "Workday_end_after_start" CHECK ("endedAt" IS NULL OR "endedAt" >= "startedAt"),
  ADD CONSTRAINT "Workday_active_key_matches" CHECK ("activeEmployeeId" IS NULL OR "activeEmployeeId" = "employeeId"),
  ADD CONSTRAINT "Workday_open_state_consistent" CHECK (
    ("status" = 'COMPLETED' AND "endedAt" IS NOT NULL AND "activeEmployeeId" IS NULL)
    OR ("status" <> 'COMPLETED' AND "endedAt" IS NULL AND "activeEmployeeId" IS NOT NULL)
  ),
  ADD CONSTRAINT "Workday_totals_non_negative" CHECK (
    ("productiveSeconds" IS NULL OR "productiveSeconds" >= 0)
    AND ("breakSeconds" IS NULL OR "breakSeconds" >= 0)
    AND ("breakCount" IS NULL OR "breakCount" >= 0)
  );

ALTER TABLE "WorkSession"
  ADD CONSTRAINT "WorkSession_end_after_start" CHECK ("endedAt" IS NULL OR "endedAt" >= "startedAt"),
  ADD CONSTRAINT "WorkSession_open_key_consistent" CHECK (
    ("endedAt" IS NULL AND "openWorkdayId" = "workdayId") OR ("endedAt" IS NOT NULL AND "openWorkdayId" IS NULL)
  );

ALTER TABLE "BreakSession"
  ADD CONSTRAINT "BreakSession_end_after_start" CHECK ("endedAt" IS NULL OR "endedAt" >= "startedAt"),
  ADD CONSTRAINT "BreakSession_open_key_consistent" CHECK (
    ("endedAt" IS NULL AND "openWorkdayId" = "workdayId") OR ("endedAt" IS NOT NULL AND "openWorkdayId" IS NULL)
  );

ALTER TABLE "TeamMembership"
  ADD CONSTRAINT "TeamMembership_end_after_start" CHECK ("endedAt" IS NULL OR "endedAt" >= "startedAt"),
  ADD CONSTRAINT "TeamMembership_active_key_consistent" CHECK (
    ("endedAt" IS NULL AND "activeEmployeeId" = "employeeId") OR ("endedAt" IS NOT NULL AND "activeEmployeeId" IS NULL)
  );

ALTER TABLE "OrganizationSetting"
  ADD CONSTRAINT "OrganizationSetting_singleton" CHECK ("id" = 1),
  ADD CONSTRAINT "OrganizationSetting_stale_hours_range" CHECK ("staleWorkdayHours" BETWEEN 1 AND 48);
