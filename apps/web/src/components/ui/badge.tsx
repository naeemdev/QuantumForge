import type { ReactNode } from "react";
import { cn } from "./cn";

export type Tone = "neutral" | "success" | "danger" | "warning" | "info";

const tones: Record<Tone, string> = {
  neutral: "bg-surface-2 text-fg-2 border-border",
  success: "bg-success-soft text-success border-transparent",
  danger: "bg-danger-soft text-danger border-transparent",
  warning: "bg-warning-soft text-warning border-transparent",
  info: "bg-info-soft text-info border-transparent",
};

export function Badge({ tone = "neutral", children, className, dot }: { tone?: Tone; children: ReactNode; className?: string; dot?: boolean }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded border px-1.5 py-px text-[11px] font-medium leading-4 whitespace-nowrap", tones[tone], className)}>
      {dot && <span className="size-1.5 rounded-full bg-current" aria-hidden />}
      {children}
    </span>
  );
}
