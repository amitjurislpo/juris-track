"use client";

import { useState, type ReactNode } from "react";
import { Button } from "./button";
import { TextArea } from "./form";
import { Modal } from "./modal";

/** Confirmation for destructive or consequential actions, with optional reason. */
export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmLabel = "Confirm",
  tone = "danger",
  loading,
  reason,
  children,
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: (reason: string) => void;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: string;
  tone?: "danger" | "primary";
  loading?: boolean;
  /** Ask for a reason (e.g. for the audit log). */
  reason?: { label: string; required?: boolean };
  children?: ReactNode;
}) {
  const [text, setText] = useState("");
  const invalid = reason?.required && text.trim().length < 5;

  const close = () => {
    setText("");
    onClose();
  };

  return (
    <Modal
      open={open}
      onClose={close}
      title={title}
      description={description}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={close} disabled={loading}>
            Cancel
          </Button>
          <Button variant={tone} onClick={() => onConfirm(text.trim())} loading={loading} disabled={!!invalid}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {(children || reason) && (
        <div className="space-y-4">
          {children}
          {reason && (
            <TextArea
              label={reason.label}
              value={text}
              onChange={(e) => setText(e.target.value)}
              required={reason.required}
              maxLength={500}
              hint={reason.required ? "At least 5 characters. Recorded in the audit log." : "Optional. Recorded in the audit log."}
            />
          )}
        </div>
      )}
    </Modal>
  );
}
