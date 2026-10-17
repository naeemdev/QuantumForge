"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";

/** Accessible modal built on the native <dialog> element (focus trap and Esc handling come from the browser). */
export function Dialog({ open, onClose, title, description, children, footer }: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      onClose={onClose}
      onClick={(e) => e.target === ref.current && onClose()}
      className="m-auto w-full max-w-lg rounded-lg border border-border bg-surface p-0 text-fg shadow-xl backdrop:bg-black/40"
    >
      <div className="flex items-start justify-between border-b border-border px-5 py-4">
        <div>
          <h2 className="text-sm font-semibold">{title}</h2>
          {description && <p className="mt-0.5 text-[13px] text-fg-3">{description}</p>}
        </div>
        <button onClick={onClose} className="rounded p-1 text-fg-3 hover:bg-surface-hover hover:text-fg" aria-label="Close">
          <X className="size-4" />
        </button>
      </div>
      <div className="space-y-4 px-5 py-4">{children}</div>
      {footer && <div className="flex justify-end gap-2 border-t border-border px-5 py-3">{footer}</div>}
    </dialog>
  );
}
