"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Activity } from "lucide-react";
import type { JobState } from "@quantumforge/contracts";
import { useJobs, useProjects } from "@/lib/queries";
import { formatDuration, formatRelative } from "@/lib/format";
import { JobStateBadge, TestCounts } from "@/components/job-state";
import { Card } from "@/components/ui/card";
import { Select } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { EmptyState, ErrorState, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { Button } from "@/components/ui/button";

const filters: { label: string; states: JobState[] }[] = [
  { label: "All states", states: [] },
  { label: "Active", states: ["QUEUED", "LEASED", "RUNNING"] },
  { label: "Passed", states: ["SUCCEEDED"] },
  { label: "Failed", states: ["FAILED"] },
  { label: "Timed out / infrastructure", states: ["TIMED_OUT", "INFRA_ERROR"] },
  { label: "Cancelled", states: ["CANCELLED"] },
];

function TestRuns() {
  const initialProject = useSearchParams().get("project") ?? "";
  const [projectId, setProjectId] = useState(initialProject);
  const [filterIndex, setFilterIndex] = useState(0);
  const [page, setPage] = useState(0);
  const projects = useProjects(undefined, 0);
  const jobs = useJobs({ projectId: projectId || undefined, states: filters[filterIndex]?.states, page });
  const projectName = (id: string) => projects.data?.items.find((p) => p.id === id)?.name ?? id.slice(-8);

  return (
    <>
      <PageHeader title="Test runs" description="Every execution, with the exact snapshot, runtime and limits that produced it." />
      <Card>
        <div className="flex flex-wrap gap-2 border-b border-border p-3">
          <Select value={projectId} onChange={(e) => { setProjectId(e.target.value); setPage(0); }} className="w-56" aria-label="Project">
            <option value="">All projects</option>
            {projects.data?.items.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
          <Select value={filterIndex} onChange={(e) => { setFilterIndex(Number(e.target.value)); setPage(0); }} className="w-56" aria-label="State">
            {filters.map((f, i) => <option key={f.label} value={i}>{f.label}</option>)}
          </Select>
        </div>
        {jobs.isPending ? <Spinner /> : jobs.isError ? <div className="p-4"><ErrorState error={jobs.error} onRetry={() => jobs.refetch()} /></div> : jobs.data.items.length === 0 ? (
          <EmptyState icon={<Activity className="size-5" />} title="No test runs match" description="Start a run from a project page." />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Run</Th>
                  <Th>Project</Th>
                  <Th>Status</Th>
                  <Th>Tests</Th>
                  <Th>Attempts</Th>
                  <Th className="text-right">Duration</Th>
                  <Th className="text-right">Queued</Th>
                </tr>
              </thead>
              <tbody>
                {jobs.data.items.map((j) => (
                  <Tr key={j.id}>
                    <Td>
                      <Link href={`/test-runs/${j.id}`} className="font-mono text-xs text-fg hover:underline">{j.id.slice(-8)}</Link>
                      <p className="font-mono text-[11px] text-fg-3">snapshot …{j.snapshotId.slice(-8)}</p>
                    </Td>
                    <Td><Link href={`/projects/${j.projectId}`} className="hover:text-fg hover:underline">{projectName(j.projectId)}</Link></Td>
                    <Td><JobStateBadge state={j.state} /></Td>
                    <Td><TestCounts tests={j.resultSummary?.tests as Record<string, unknown> | undefined} /></Td>
                    <Td className="tabular text-xs">{j.attemptCount} / {j.maxAttempts}</Td>
                    <Td className="tabular text-right text-xs">{formatDuration(j.resultSummary?.durationMs as number | undefined)}</Td>
                    <Td className="text-right text-xs">{formatRelative(j.queuedAt)}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
            <div className="flex items-center justify-between border-t border-border px-4 py-2 text-xs text-fg-3">
              <span>{jobs.data.total} runs</span>
              <div className="flex gap-2">
                <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
                <Button size="sm" disabled={(page + 1) * jobs.data.size >= jobs.data.total} onClick={() => setPage((p) => p + 1)}>Next</Button>
              </div>
            </div>
          </>
        )}
      </Card>
    </>
  );
}

export default function TestRunsPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <TestRuns />
    </Suspense>
  );
}
