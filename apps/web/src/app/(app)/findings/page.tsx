"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { ACTIVE_FINDING_STATUSES, SEVERITIES } from "@quantumforge/contracts";
import { useFindingCounts, useFindings } from "@/lib/queries";
import { formatRelative } from "@/lib/format";
import { EvidenceNote, FindingStatusBadge, SeverityBadge } from "@/components/findings";
import { ProjectPicker, useSelectedProject } from "@/components/project-picker";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input, Select } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { EmptyState, ErrorState, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { cn } from "@/components/ui/cn";

const statusFilters: { label: string; statuses: string[] }[] = [
  { label: "Active", statuses: [...ACTIVE_FINDING_STATUSES] },
  { label: "All", statuses: [] },
  { label: "Fixed", statuses: ["FIXED"] },
  { label: "Dismissed", statuses: ["FALSE_POSITIVE", "ACCEPTED_RISK"] },
];

function Findings() {
  const { projectId, projects } = useSelectedProject();
  const [statusIndex, setStatusIndex] = useState(0);
  const [severity, setSeverity] = useState<string[]>([]);
  const [ruleId, setRuleId] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const counts = useFindingCounts(projectId);
  const findings = useFindings(projectId, { status: statusFilters[statusIndex]?.statuses, severity, ruleId, q, page });

  const toggleSeverity = (s: string) => {
    setPage(0);
    setSeverity((prev) => (prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s]));
  };

  return (
    <>
      <PageHeader title="Findings" description="Issues detected by analyses, tracked across snapshots with their full status history." actions={<ProjectPicker projectId={projectId} projects={projects} />} />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {SEVERITIES.map((s) => (
          <button key={s} onClick={() => toggleSeverity(s)} aria-pressed={severity.includes(s)}
            className={cn("flex items-center gap-2 rounded-md border px-3 py-1.5 text-[13px] capitalize", severity.includes(s) ? "border-accent bg-accent-soft text-fg" : "border-border bg-surface text-fg-2 hover:bg-surface-hover")}>
            {s} <span className="tabular text-xs text-fg-3">{counts.data?.bySeverity[s] ?? 0}</span>
          </button>
        ))}
        <span className="ml-1 text-xs text-fg-3">active findings by severity</span>
      </div>
      <Card>
        <div className="flex flex-wrap gap-2 border-b border-border p-3">
          <Select value={statusIndex} onChange={(e) => { setStatusIndex(Number(e.target.value)); setPage(0); }} className="w-36" aria-label="Status">
            {statusFilters.map((f, i) => <option key={f.label} value={i}>{f.label}</option>)}
          </Select>
          <Select value={ruleId} onChange={(e) => { setRuleId(e.target.value); setPage(0); }} className="w-56" aria-label="Rule">
            <option value="">All rules</option>
            {Object.keys(counts.data?.byRule ?? {}).sort().map((r) => <option key={r} value={r}>{r}</option>)}
          </Select>
          <Input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Search message, file or code" className="w-72" aria-label="Search findings" />
        </div>
        {!projectId ? <EmptyState title="No projects" /> : findings.isPending ? <Spinner /> : findings.isError ? (
          <div className="p-4"><ErrorState error={findings.error} /></div>
        ) : findings.data.items.length === 0 ? (
          <EmptyState icon={<ShieldCheck className="size-5" />} title="No findings match" description="Analyse a snapshot from Code Intelligence, or change the filters." />
        ) : (
          <>
            <Table>
              <thead><tr><Th>Severity</Th><Th>Finding</Th><Th>Location</Th><Th>Status</Th><Th className="text-right">Last seen</Th></tr></thead>
              <tbody>
                {findings.data.items.map((f) => (
                  <Tr key={f.id}>
                    <Td><SeverityBadge severity={f.severity} /></Td>
                    <Td className="max-w-md">
                      <Link href={`/findings/${f.id}`} className="block truncate font-medium text-fg hover:underline">{f.message}</Link>
                      <p className="text-xs text-fg-3"><span className="font-mono">{f.ruleId}</span> · {f.ruleName} · {f.confidence} confidence</p>
                    </Td>
                    <Td className="font-mono text-xs">{f.path}:{f.startLine}</Td>
                    <Td><FindingStatusBadge status={f.status} /></Td>
                    <Td className="text-right text-xs">{formatRelative(f.lastSeenAt)}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
            <div className="flex items-center justify-between border-t border-border px-4 py-2 text-xs text-fg-3">
              <span>{findings.data.total} findings</span>
              <div className="flex gap-2">
                <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
                <Button size="sm" disabled={(page + 1) * findings.data.size >= findings.data.total} onClick={() => setPage((p) => p + 1)}>Next</Button>
              </div>
            </div>
          </>
        )}
      </Card>
      <EvidenceNote className="mt-3" />
    </>
  );
}

export default function FindingsPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <Findings />
    </Suspense>
  );
}
