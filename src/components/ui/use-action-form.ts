"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { ActionResult, FieldErrors } from "@/lib/errors";
import { useToast } from "./toast";

/**
 * Submits a FormData server action, tracking pending state, field errors and
 * toasts in one place. `pending` disables the submit button, preventing
 * duplicate submissions.
 */
export function useActionForm<T>(
  action: (form: FormData) => Promise<ActionResult<T>>,
  opts: { onSuccess?: (data: T) => void; refresh?: boolean; toastOnSuccess?: boolean } = {},
) {
  const { onSuccess, refresh = true, toastOnSuccess = true } = opts;
  const [pending, start] = useTransition();
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const toast = useToast();
  const router = useRouter();

  const submit = (form: FormData) =>
    start(async () => {
      const res = await action(form);
      if (res.ok) {
        setErrors({});
        setFormError(null);
        if (toastOnSuccess) toast.success(res.message ?? "Saved.");
        onSuccess?.(res.data);
        if (refresh) router.refresh();
      } else {
        setErrors(res.fieldErrors ?? {});
        setFormError(res.error);
      }
    });

  const reset = () => {
    setErrors({});
    setFormError(null);
  };

  return { submit, pending, errors, formError, reset };
}

/** Runs a non-form server action with toasts + refresh. */
export function useActionCall() {
  const [pending, start] = useTransition();
  const toast = useToast();
  const router = useRouter();
  const call = <T,>(fn: () => Promise<ActionResult<T>>, onSuccess?: (data: T) => void) =>
    start(async () => {
      const res = await fn();
      if (res.ok) {
        toast.success(res.message ?? "Done.");
        onSuccess?.(res.data);
        router.refresh();
      } else {
        toast.error(res.error);
      }
    });
  return { call, pending };
}
