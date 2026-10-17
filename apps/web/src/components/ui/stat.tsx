import type { ReactNode } from "react";

export function Stat({ label, value, detail }: { label: string; value: ReactNode; detail?: ReactNode }) {
  return (
    <div className="rounded-lg border border-border bg-surface px-4 py-3">
      <p className="text-xs text-fg-3">{label}</p>
      <p className="tabular mt-1 text-xl font-semibold tracking-tight text-fg">{value}</p>
      {detail && <p className="mt-0.5 text-xs text-fg-3">{detail}</p>}
    </div>
  );
}
