"use client";

import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

const control =
  "block w-full rounded-lg border border-border-strong bg-surface px-3 text-sm text-ink placeholder:text-ink-3 " +
  "shadow-sm transition-colors focus:border-focus focus:outline-none focus:ring-2 focus:ring-focus/20 " +
  "disabled:cursor-not-allowed disabled:bg-surface-3 aria-[invalid=true]:border-danger";

export function Field({
  label,
  hint,
  error,
  children,
  htmlFor,
  required,
  className,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: string[] | string;
  children: ReactNode;
  htmlFor?: string;
  required?: boolean;
  className?: string;
}) {
  const message = Array.isArray(error) ? error[0] : error;
  return (
    <div className={cn("space-y-1.5", className)}>
      <label htmlFor={htmlFor} className="block text-sm font-medium text-ink-2">
        {label}
        {required && <span className="ml-0.5 text-danger" aria-hidden="true">*</span>}
      </label>
      {children}
      {message ? (
        <p className="text-xs text-danger" role="alert">{message}</p>
      ) : hint ? (
        <p className="text-xs text-ink-3">{hint}</p>
      ) : null}
    </div>
  );
}

type WithField = { label?: ReactNode; hint?: ReactNode; error?: string[] | string; fieldClassName?: string };

export function TextInput({ label, hint, error, fieldClassName, className, id, required, ...rest }: InputHTMLAttributes<HTMLInputElement> & WithField) {
  const auto = useId();
  const inputId = id ?? auto;
  const input = (
    <input id={inputId} required={required} aria-invalid={error ? true : undefined} className={cn(control, "h-10", className)} {...rest} />
  );
  if (!label) return input;
  return (
    <Field label={label} hint={hint} error={error} htmlFor={inputId} required={required} className={fieldClassName}>
      {input}
    </Field>
  );
}

export function SelectInput({
  label,
  hint,
  error,
  fieldClassName,
  className,
  id,
  required,
  children,
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & WithField) {
  const auto = useId();
  const inputId = id ?? auto;
  const select = (
    <select id={inputId} required={required} aria-invalid={error ? true : undefined} className={cn(control, "h-10 pr-8", className)} {...rest}>
      {children}
    </select>
  );
  if (!label) return select;
  return (
    <Field label={label} hint={hint} error={error} htmlFor={inputId} required={required} className={fieldClassName}>
      {select}
    </Field>
  );
}

export function TextArea({ label, hint, error, fieldClassName, className, id, required, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement> & WithField) {
  const auto = useId();
  const inputId = id ?? auto;
  const area = (
    <textarea id={inputId} required={required} aria-invalid={error ? true : undefined} className={cn(control, "min-h-20 py-2", className)} {...rest} />
  );
  if (!label) return area;
  return (
    <Field label={label} hint={hint} error={error} htmlFor={inputId} required={required} className={fieldClassName}>
      {area}
    </Field>
  );
}

export function Checkbox({ label, description, className, ...rest }: InputHTMLAttributes<HTMLInputElement> & { label: ReactNode; description?: ReactNode }) {
  return (
    <label className={cn("flex cursor-pointer items-start gap-3", className)}>
      <input type="checkbox" className="mt-0.5 size-4 rounded border-border-strong accent-[var(--brand)]" {...rest} />
      <span>
        <span className="block text-sm font-medium text-ink">{label}</span>
        {description && <span className="block text-xs text-ink-3">{description}</span>}
      </span>
    </label>
  );
}

export function FormError({ message }: { message?: string | null }) {
  if (!message) return null;
  return (
    <div role="alert" className="rounded-lg border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger">
      {message}
    </div>
  );
}
