"use client";

import Link from "next/link";
import { Fragment, Suspense, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowDownLeft, ArrowUpRight, Braces, Play } from "lucide-react";
import type { CodeIndexView, CodeSymbolView } from "@quantumforge/contracts";
import { ApiError } from "@/lib/api";
import { useAnalysisJobs, useCalls, useCodeIndex, useDependencies, useRevisions, useSubmitAnalysis, useSymbols } from "@/lib/queries";
import { useSession } from "@/lib/session";
import { formatDuration, formatRelative } from "@/lib/format";
import { JobStateBadge } from "@/components/job-state";
import { ProjectPicker, useSelectedProject } from "@/components/project-picker";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { Input, Select } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { Stat } from "@/components/ui/stat";
import { EmptyState, ErrorState, InlineError, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { cn } from "@/components/ui/cn";

type Tab = "symbols" | "dependencies" | "history";

const levelTone = { semantic: "success", syntactic: "info", none: "neutral" } as const;

function IndexSummary({ index }: { index: CodeIndexView }) {
  const stats = index.stats as { filesSeen?: number; filesAnalysed?: number; durationMs?: number; truncated?: boolean };
  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <div className="grid grid-cols-2 gap-3 lg:col-span-2">
        <Stat label="Files analysed" value={`${stats.filesAnalysed ?? index.fileCount}`} detail={stats.truncated ? "Limit reached; some files skipped" : `of ${stats.filesSeen ?? "?"} source files`} />
        <Stat label="Symbols" value={index.symbolCount} detail="Functions, classes, methods, types" />
        <Stat label="Import edges" value={index.importCount} detail="Internal and external modules" />
        <Stat label="Call edges" value={index.callCount} detail={`Analysis took ${formatDuration(stats.durationMs)}`} />
      </div>
      <Card>
        <CardHeader title="Adapter capabilities" description={`${index.adapterId} ${index.adapterVersion} · ${index.toolchain}`} />
        <CardBody className="space-y-3">
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(index.capabilities).map(([k, v]) => (
              <Badge key={k} tone={levelTone[String(v) as keyof typeof levelTone] ?? "neutral"}>{k}: {String(v)}</Badge>
            ))}
          </div>
          <details className="text-xs text-fg-2">
            <summary className="cursor-pointer text-fg-3 hover:text-fg">Known limitations ({index.limitations.length})</summary>
            <ul className="mt-2 list-disc space-y-1 pl-4">{index.limitations.map((l) => <li key={l}>{l}</li>)}</ul>
          </details>
        </CardBody>
      </Card>
    </div>
  );
}

function CallsPanel({ projectId, symbol }: { projectId: string; symbol: CodeSymbolView }) {
  const calls = useCalls(projectId, symbol.key);
  if (calls.isPending) return <Spinner />;
  if (calls.isError) return <ErrorState error={calls.error} />;
  const callees = calls.data.filter((c) => c.from === symbol.key);
  const callers = calls.data.filter((c) => c.to === symbol.key);
  const list = (items: typeof callees, pick: (c: (typeof callees)[number]) => string, icon: React.ReactNode, empty: string) =>
    items.length === 0 ? <p className="text-xs text-fg-3">{empty}</p> : (
      <ul className="space-y-1">
        {items.map((c) => (
          <li key={pick(c)} className="flex items-center gap-1.5 font-mono text-xs text-fg-2">{icon}{pick(c).split("#")[1]} <span className="text-fg-3">{pick(c).split("#")[0]}</span></li>
        ))}
      </ul>
    );
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <div>
        <p className="mb-2 text-xs font-medium text-fg-2">Called by</p>
        {list(callers, (c) => c.from, <ArrowDownLeft className="size-3 text-fg-3" />, "No resolved callers.")}
      </div>
      <div>
        <p className="mb-2 text-xs font-medium text-fg-2">Calls</p>
        {list(callees, (c) => c.to, <ArrowUpRight className="size-3 text-fg-3" />, "No resolved calls.")}
      </div>
    </div>
  );
}

function SymbolsTab({ projectId, snapshotId }: { projectId: string; snapshotId: string }) {
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [selected, setSelected] = useState<CodeSymbolView | null>(null);
  const symbols = useSymbols(projectId, { q, kind });
  return (
    <>
      <div className="flex flex-wrap gap-2 border-b border-border p-3">
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search symbols" className="w-64" aria-label="Search symbols" />
        <Select value={kind} onChange={(e) => setKind(e.target.value)} className="w-40" aria-label="Kind">
          <option value="">All kinds</option>
          {["function", "method", "class", "interface", "type", "enum", "variable"].map((k) => <option key={k} value={k}>{k}</option>)}
        </Select>
      </div>
      {symbols.isPending ? <Spinner /> : symbols.isError ? <div className="p-4"><ErrorState error={symbols.error} /></div> : symbols.data.items.length === 0 ? (
        <EmptyState title="No symbols match" />
      ) : (
        <Table>
          <thead><tr><Th>Symbol</Th><Th>Kind</Th><Th>Location</Th><Th className="text-right">Complexity</Th></tr></thead>
          <tbody>
            {symbols.data.items.map((s) => (
              <Fragment key={s.key}>
                <Tr className="cursor-pointer" onClick={() => setSelected(selected?.key === s.key ? null : s)}>
                  <Td>
                    <span className="font-mono text-xs text-fg">{s.container ? `${s.container}.` : ""}{s.name}</span>
                    {s.signature && <span className="ml-1 font-mono text-[11px] text-fg-3">{s.signature.length > 70 ? s.signature.slice(0, 70) + "…" : s.signature}</span>}
                    {!s.exported && <span className="ml-2 text-[11px] text-fg-3">internal</span>}
                  </Td>
                  <Td><Badge>{s.kind}</Badge></Td>
                  <Td>
                    <Link onClick={(e) => e.stopPropagation()} href={`/projects/${projectId}/source?snapshot=${snapshotId}&path=${encodeURIComponent(s.path)}&line=${s.startLine}`} className="font-mono text-xs hover:text-fg hover:underline">
                      {s.path}:{s.startLine}
                    </Link>
                  </Td>
                  <Td className={cn("tabular text-right text-xs", (s.complexity ?? 0) >= 10 && "font-medium text-warning")}>{s.complexity ?? "—"}</Td>
                </Tr>
                {selected?.key === s.key && (
                  <tr><td colSpan={4} className="border-b border-border bg-surface-2 px-4 py-3"><CallsPanel projectId={projectId} symbol={s} /></td></tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </Table>
      )}
      {symbols.data && <p className="border-t border-border px-4 py-2 text-xs text-fg-3">{symbols.data.total} symbols{symbols.data.total > symbols.data.items.length ? ` · showing ${symbols.data.items.length}` : ""}</p>}
    </>
  );
}

function DependenciesTab({ projectId }: { projectId: string }) {
  const graph = useDependencies(projectId);
  const [file, setFile] = useState<string | null>(null);
  const related = useMemo(() => {
    const edges = graph.data?.edges ?? [];
    return file ? { imports: edges.filter((e) => e.from === file), importedBy: edges.filter((e) => e.to === file) } : null;
  }, [file, graph.data]);
  if (graph.isPending) return <Spinner />;
  if (graph.isError) return <div className="p-4"><ErrorState error={graph.error} /></div>;
  const g = graph.data;
  return (
    <div className="grid lg:grid-cols-3">
      <div className="lg:col-span-2 lg:border-r lg:border-border">
        <Table>
          <thead><tr><Th>File</Th><Th className="text-right">Imports</Th><Th className="text-right">Imported by</Th><Th className="text-right">Symbols</Th><Th className="text-right">Open findings</Th></tr></thead>
          <tbody>
            {g.nodes.map((n) => (
              <Tr key={n.path} className={cn("cursor-pointer", file === n.path && "bg-accent-soft")} onClick={() => setFile(n.path)}>
                <Td className="font-mono text-xs text-fg">{n.path}</Td>
                <Td className="tabular text-right text-xs">{n.fanOut}</Td>
                <Td className="tabular text-right text-xs">{n.fanIn}</Td>
                <Td className="tabular text-right text-xs">{n.symbolCount}</Td>
                <Td className={cn("tabular text-right text-xs", n.openFindings > 0 && "font-medium text-danger")}>{n.openFindings}</Td>
              </Tr>
            ))}
          </tbody>
        </Table>
      </div>
      <div className="space-y-5 p-4">
        {related ? (
          <div className="space-y-4">
            <p className="font-mono text-xs font-medium text-fg">{file}</p>
            <div>
              <p className="mb-1.5 text-xs font-medium text-fg-2">Imports</p>
              {related.imports.length ? related.imports.map((e) => <p key={e.to} className="flex items-center gap-1.5 font-mono text-xs text-fg-2"><ArrowUpRight className="size-3 text-fg-3" />{e.to}</p>) : <p className="text-xs text-fg-3">No internal imports.</p>}
            </div>
            <div>
              <p className="mb-1.5 text-xs font-medium text-fg-2">Imported by</p>
              {related.importedBy.length ? related.importedBy.map((e) => <p key={e.from} className="flex items-center gap-1.5 font-mono text-xs text-fg-2"><ArrowDownLeft className="size-3 text-fg-3" />{e.from}</p>) : <p className="text-xs text-fg-3">Not imported by any analysed file.</p>}
            </div>
          </div>
        ) : <p className="text-xs text-fg-3">Select a file to see its dependencies.</p>}
        <div>
          <p className="mb-1.5 text-xs font-medium text-fg-2">External modules</p>
          {g.external.length === 0 ? <p className="text-xs text-fg-3">None.</p> : (
            <ul className="space-y-1">{g.external.map((x) => <li key={x.packageName} className="flex justify-between font-mono text-xs text-fg-2"><span>{x.packageName}</span><span className="text-fg-3">{x.importedBy} files</span></li>)}</ul>
          )}
          {g.unresolved > 0 && <p className="mt-2 text-xs text-warning">{g.unresolved} relative imports could not be resolved.</p>}
        </div>
      </div>
    </div>
  );
}

function HistoryTab({ projectId }: { projectId: string }) {
  const jobs = useAnalysisJobs(projectId);
  if (jobs.isPending) return <Spinner />;
  if (jobs.isError) return <div className="p-4"><ErrorState error={jobs.error} /></div>;
  if (jobs.data.items.length === 0) return <EmptyState title="No analyses yet" />;
  return (
    <Table>
      <thead><tr><Th>Analysis</Th><Th>Status</Th><Th>Findings</Th><Th className="text-right">Duration</Th><Th className="text-right">Queued</Th></tr></thead>
      <tbody>
        {jobs.data.items.map((j) => {
          const a = j.resultSummary?.analysis as { findings?: number; newFindings?: number; fixed?: number } | undefined;
          return (
            <Tr key={j.id}>
              <Td><Link href={`/test-runs/${j.id}`} className="font-mono text-xs text-fg hover:underline">{j.id.slice(-8)}</Link></Td>
              <Td><JobStateBadge state={j.state} jobType={j.jobType} /></Td>
              <Td className="text-xs">{a ? <>{a.findings} detected · {a.newFindings} new · {a.fixed} fixed</> : "—"}</Td>
              <Td className="tabular text-right text-xs">{formatDuration(j.resultSummary?.durationMs as number | undefined)}</Td>
              <Td className="text-right text-xs">{formatRelative(j.queuedAt)}</Td>
            </Tr>
          );
        })}
      </tbody>
    </Table>
  );
}

function CodeIntelligence() {
  const { can } = useSession();
  const router = useRouter();
  const { projectId, projects } = useSelectedProject();
  const index = useCodeIndex(projectId);
  const revisions = useRevisions(projectId ?? "");
  const submit = useSubmitAnalysis(projectId ?? "");
  const [tab, setTab] = useState<Tab>("symbols");
  const latestSnapshot = revisions.data?.items[0];
  const notIndexed = index.error instanceof ApiError && index.error.status === 404;

  async function analyse() {
    const job = await submit.mutateAsync({ idempotencyKey: crypto.randomUUID(), snapshotId: latestSnapshot?.id });
    router.push(`/test-runs/${job.id}`);
  }

  return (
    <>
      <PageHeader
        title="Code Intelligence"
        description="Symbols, dependencies and call relationships extracted from your latest analysed snapshot."
        actions={
          <>
            <ProjectPicker projectId={projectId} projects={projects} />
            {can("job.submit") && (
              <Button variant="primary" onClick={() => void analyse()} loading={submit.isPending} disabled={!latestSnapshot}>
                <Play className="size-4" /> Analyse latest snapshot
              </Button>
            )}
          </>
        }
      />
      <InlineError error={submit.error} />
      {!projectId ? (
        projects.isPending ? <Spinner /> : <EmptyState icon={<Braces className="size-5" />} title="No projects" description="Create a project and import a snapshot first." />
      ) : index.isPending ? <Spinner /> : notIndexed ? (
        <Card>
          <EmptyState icon={<Braces className="size-5" />} title="Not analysed yet" description="Run an analysis to index symbols, imports and calls. The TypeScript adapter runs in an isolated worker." />
          <div className="border-t border-border"><HistoryTab projectId={projectId} /></div>
        </Card>
      ) : index.isError ? <ErrorState error={index.error} /> : (
        <div className="space-y-6">
          <IndexSummary index={index.data} />
          <Card>
            <div className="flex gap-1 border-b border-border px-2" role="tablist">
              {([["symbols", "Symbols"], ["dependencies", "Dependencies"], ["history", "Analysis history"]] as const).map(([id, label]) => (
                <button key={id} role="tab" aria-selected={tab === id} onClick={() => setTab(id)}
                  className={cn("-mb-px border-b-2 px-3 py-2.5 text-[13px]", tab === id ? "border-accent font-medium text-fg" : "border-transparent text-fg-3 hover:text-fg")}>
                  {label}
                </button>
              ))}
            </div>
            {tab === "symbols" && <SymbolsTab projectId={projectId} snapshotId={index.data.snapshotId} />}
            {tab === "dependencies" && <DependenciesTab projectId={projectId} />}
            {tab === "history" && <HistoryTab projectId={projectId} />}
          </Card>
        </div>
      )}
    </>
  );
}

export default function CodeIntelligencePage() {
  return (
    <Suspense fallback={<Spinner />}>
      <CodeIntelligence />
    </Suspense>
  );
}
