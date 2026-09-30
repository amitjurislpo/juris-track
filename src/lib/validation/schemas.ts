import { z } from "zod";
import { isValidTimeZone } from "@/lib/time/tz";

const trimmed = (max: number, label: string) =>
  z.string().trim().min(1, `${label} is required`).max(max, `${label} must be at most ${max} characters`);

/** Empty strings from forms become undefined. */
const optionalTrimmed = (max: number, label: string) =>
  z
    .string()
    .trim()
    .max(max, `${label} must be at most ${max} characters`)
    .optional()
    .transform((v) => (v ? v : undefined));

const email = z.string().trim().toLowerCase().pipe(z.email("Enter a valid email address")).pipe(z.string().max(254));

const id = z.string().trim().min(1).max(64);
const optionalId = z
  .string()
  .trim()
  .max(64)
  .optional()
  .transform((v) => (v ? v : undefined));

export const RoleSchema = z.enum(["ADMIN", "MANAGER", "EMPLOYEE"]);

export const PasswordSchema = z
  .string()
  .min(10, "Use at least 10 characters")
  .max(128, "Use at most 128 characters")
  .regex(/[a-z]/, "Include a lowercase letter")
  .regex(/[A-Z]/, "Include an uppercase letter")
  .regex(/\d/, "Include a number");

export const LoginSchema = z.object({
  email,
  password: z.string().min(1, "Password is required").max(128),
});

export const TimeCommandSchema = z.enum(["start", "break", "resume", "end"]);

const checkbox = z
  .union([z.literal("on"), z.literal("true"), z.literal("false"), z.boolean()])
  .optional()
  .transform((v) => v === true || v === "on" || v === "true");

export const EmployeeCreateSchema = z.object({
  firstName: trimmed(60, "First name"),
  lastName: trimmed(60, "Last name"),
  email,
  employeeCode: optionalTrimmed(32, "Employee ID").pipe(
    z.string().regex(/^[A-Za-z0-9-_]+$/, "Use letters, numbers, dashes or underscores").optional(),
  ),
  role: RoleSchema,
  teamId: optionalId,
  isActive: checkbox,
  /** default = organization default password, generated = random, custom = typed by the admin. */
  passwordMode: z.enum(["default", "generated", "custom"]).default("default"),
  password: z
    .string()
    .optional()
    .transform((v) => (v ? v : undefined))
    .pipe(PasswordSchema.optional()),
});
export type EmployeeCreateInput = z.infer<typeof EmployeeCreateSchema>;

export const EmployeeUpdateSchema = EmployeeCreateSchema.omit({ password: true, passwordMode: true, teamId: true, isActive: true }).extend({
  id,
});
export type EmployeeUpdateInput = z.infer<typeof EmployeeUpdateSchema>;

export const AssignTeamSchema = z.object({ employeeId: id, teamId: optionalId });

export const SetActiveSchema = z.object({
  id,
  active: checkbox,
  reason: optionalTrimmed(500, "Reason"),
});

export const TeamSchema = z.object({
  name: trimmed(80, "Team name"),
  description: optionalTrimmed(500, "Description"),
  managerId: optionalId,
});
export type TeamInput = z.infer<typeof TeamSchema>;

export const TeamUpdateSchema = TeamSchema.extend({ id });

export const ReasonSchema = z.string().trim().min(5, "Give a reason of at least 5 characters").max(500);

export const AdjustSessionSchema = z.object({
  kind: z.enum(["WORK", "BREAK"]),
  sessionId: id,
  startedAt: z.string().min(1, "Start time is required"),
  endedAt: z.string().optional(),
  reason: ReasonSchema,
});

export const CloseWorkdaySchema = z.object({
  workdayId: id,
  endAt: z.string().optional(),
  reason: ReasonSchema,
});

export const SettingsSchema = z.object({
  organizationName: trimmed(80, "Organization name"),
  timezone: z.string().trim().refine(isValidTimeZone, "Unknown timezone"),
  staleWorkdayHours: z.coerce.number().int().min(1, "Minimum is 1 hour").max(48, "Maximum is 48 hours"),
  displayTimezones: z
    .array(z.string().trim().refine(isValidTimeZone, "Unknown timezone"))
    .max(8, "Offer at most 8 display timezones")
    .transform((list) => [...new Set(list)]),
});

export const ChangePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, "Current password is required").max(128),
    newPassword: PasswordSchema,
    confirmPassword: z.string(),
  })
  .refine((v) => v.newPassword === v.confirmPassword, { path: ["confirmPassword"], message: "Passwords do not match" })
  .refine((v) => v.newPassword !== v.currentPassword, { path: ["newPassword"], message: "Choose a different password" });

/** Reads a FormData into a plain object for schema parsing. */
export function formToObject(form: FormData): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of form.entries()) if (typeof v === "string") out[k] = v;
  return out;
}
