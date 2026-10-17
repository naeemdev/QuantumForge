import type { FindingStatus } from "@quantumforge/contracts";
import { Badge, type Tone } from "./ui/badge";
import { cn } from "./ui/cn";

const severityTone: Record<string, Tone> = { critical: "danger", high: "danger", medium: "warning", low: "info", info: "neutral" };

export function SeverityBadge({ severity }: { severity: string }) {
  return (
    <Badge tone={severityTone[severity] ?? "neutral"} className={cn(severity === "critical" && "font-semibold")}>
      {severity.charAt(0).toUpperCase() + severity.slice(1)}
    </Badge>
  );
}

const statusPresentation: Record<FindingStatus, { tone: Tone; label: string }> = {
  OPEN: { tone: "info", label: "Open" },
  CONFIRMED: { tone: "danger", label: "Confirmed" },
  FALSE_POSITIVE: { tone: "neutral", label: "False positive" },
  ACCEPTED_RISK: { tone: "neutral", label: "Accepted risk" },
  FIXED: { tone: "success", label: "Fixed" },
  REOPENED: { tone: "warning", label: "Reopened" },
};

export function FindingStatusBadge({ status }: { status: FindingStatus }) {
  const p = statusPresentation[status];
  return <Badge tone={p.tone}>{p.label}</Badge>;
}

export function findingStatusLabel(status: string): string {
  return statusPresentation[status as FindingStatus]?.label ?? status;
}

/** Static analysis results are suspicions until someone reproduces or confirms them. */
export function EvidenceNote({ className }: { className?: string }) {
  return (
    <p className={cn("text-xs text-fg-3", className)}>
      Static analysis findings are <span className="font-medium text-fg-2">suspected</span> issues produced by pattern rules. They are not reproduced defects.
    </p>
  );
}
