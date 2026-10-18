import Link from "next/link";
import type { FindingSource, ReproductionView } from "@quantumforge/contracts";
import { formatRelative } from "@/lib/format";
import { Badge, type Tone } from "./ui/badge";
import { CopyButton } from "./ui/copy";
import { cn } from "./ui/cn";

const oracles: Record<string, { label: string; tone: Tone }> = {
  "runtime-error": { label: "Runtime error", tone: "danger" },
  "non-finite-result": { label: "NaN / Infinity result", tone: "warning" },
  timeout: { label: "Timeout", tone: "warning" },
  crash: { label: "Crash", tone: "danger" },
};

export function OracleBadge({ oracle }: { oracle: string }) {
  const o = oracles[oracle] ?? { label: oracle, tone: "neutral" as Tone };
  return <Badge tone={o.tone}>{o.label}</Badge>;
}

const evidence: Record<string, { label: string; tone: Tone; hint: string }> = {
  SUSPECTED: { label: "Suspected", tone: "neutral", hint: "Pattern rule match; not executed" },
  OBSERVED: { label: "Observed", tone: "warning", hint: "Failed during the campaign, but not on every re-run" },
  REPRODUCED: { label: "Reproduced", tone: "danger", hint: "Failed again in every fresh re-run" },
};

export function EvidenceBadge({ level, source }: { level: string; source: FindingSource }) {
  const e = evidence[level] ?? { label: level, tone: "neutral" as Tone, hint: "" };
  return (
    <span title={e.hint}>
      <Badge tone={e.tone}>{e.label} · {source === "FUZZING" ? "fuzzing" : "static analysis"}</Badge>
    </span>
  );
}

/**
 * Renders a campaign-encoded value as a JavaScript literal. The encoding tags
 * the values JSON cannot carry: {"$qf": "NaN" | "Infinity" | "-Infinity" | "-0" | "undefined"}.
 */
export function toLiteral(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return `[${value.map(toLiteral).join(", ")}]`;
  if (typeof value === "object") {
    const tag = (value as { $qf?: unknown }).$qf;
    if (typeof tag === "string") return tag === "-0" ? "-0" : tag;
    const entries = Object.entries(value as Record<string, unknown>).map(([k, v]) => `${/^[A-Za-z_$][\w$]*$/.test(k) ? k : JSON.stringify(k)}: ${toLiteral(v)}`);
    return entries.length ? `{ ${entries.join(", ")} }` : "{}";
  }
  return typeof value === "string" ? JSON.stringify(value) : String(value);
}

export function callLiteral(exportName: string, input: unknown): string {
  return `${exportName}(${Array.isArray(input) ? input.map(toLiteral).join(", ") : ""})`;
}

/** One observed failure: minimal input, how it was reached, re-run evidence and a ready-to-run test. */
export function ReproductionCard({ reproduction: r, showFinding = false, expanded = false, className }: {
  reproduction: ReproductionView;
  showFinding?: boolean;
  /** Show the regression test without a click. */
  expanded?: boolean;
  className?: string;
}) {
  const minimal = callLiteral(r.exportName, r.input);
  const original = callLiteral(r.exportName, r.originalInput);
  const reliable = r.reproduced === r.attempts;
  return (
    <div className={cn("rounded-lg border border-border bg-surface", className)}>
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-4 py-3">
        <div className="min-w-0">
          <p className="flex flex-wrap items-center gap-2 text-[13px] font-medium text-fg">
            <span className="font-mono">{r.exportName}</span>
            <OracleBadge oracle={r.oracle} />
            <Badge tone={reliable ? "danger" : "warning"}>Reproduced {r.reproduced} of {r.attempts}</Badge>
          </p>
          <p className="mt-1 text-xs text-fg-3"><span className="font-mono">{r.path}</span> · found {formatRelative(r.createdAt)}</p>
        </div>
        {showFinding && <Link href={`/findings/${r.findingId}`} className="text-xs text-fg-2 hover:text-fg">Open finding</Link>}
      </div>
      <div className="space-y-3 px-4 py-3 text-[13px]">
        <div>
          <p className="text-xs text-fg-3">Minimal failing call</p>
          <div className="mt-1 flex items-start justify-between gap-2 rounded-md bg-surface-2 px-3 py-2">
            <code className="break-all font-mono text-xs text-fg">{minimal}</code>
            <CopyButton value={minimal} />
          </div>
        </div>
        <p className="text-fg-2">
          <span className="font-medium text-fg">{r.errorName && r.oracle === "runtime-error" ? `${r.errorName}: ` : ""}{r.message}</span>
          {r.shrinkSteps > 0 ? <> · shrunk in {r.shrinkSteps} {r.shrinkSteps === 1 ? "step" : "steps"} from <code className="font-mono text-xs">{original.length > 120 ? `${original.slice(0, 117)}...` : original}</code></> : <> · the generated input was already minimal</>}
        </p>
        {r.stack && (
          <details className="text-xs">
            <summary className="cursor-pointer text-fg-3 hover:text-fg">Stack trace</summary>
            <pre className="mt-2 overflow-auto rounded-md bg-surface-2 p-3 font-mono text-[11px] leading-relaxed text-fg-2">{r.stack}</pre>
          </details>
        )}
        <details className="text-xs" open={expanded}>
          <summary className="cursor-pointer text-fg-3 hover:text-fg">Regression test (node:test)</summary>
          <div className="mt-2 rounded-md border border-border">
            <div className="flex items-center justify-between border-b border-border px-3 py-1.5">
              <span className="text-fg-3">Save at the project root and run with <code className="font-mono">node --experimental-strip-types --test</code></span>
              <CopyButton value={r.reproductionTest} />
            </div>
            <pre className="overflow-auto p-3 font-mono text-[11px] leading-relaxed text-fg-2">{r.reproductionTest}</pre>
          </div>
        </details>
      </div>
    </div>
  );
}

export function FuzzingNote({ className }: { className?: string }) {
  return (
    <p className={cn("text-xs text-fg-3", className)}>
      Campaigns call exported functions with inputs generated from their declared types and report engine errors, NaN or Infinity results, hangs and crashes.
      A campaign with no failures means these checks found nothing in the inputs tried. It does not show the code is correct.
    </p>
  );
}
