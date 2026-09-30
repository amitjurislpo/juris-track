-- AlterTable
ALTER TABLE "OrganizationSetting" ADD COLUMN     "displayTimezones" TEXT[] DEFAULT ARRAY['Asia/Kolkata', 'America/New_York']::TEXT[];
