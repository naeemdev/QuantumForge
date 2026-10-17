"use client";

import Link from "next/link";
import { FolderGit2 } from "lucide-react";
import type { JobState } from "@quantumforge/contracts";
import { useOverview } from "@/lib/queries";
import { useSession } from "@/lib/session";
import { formatNumber, formatRelative } from "@/lib/format";
import { JobStateBadge, TestCounts } from "@/components/job-state";
import { Card, CardHeader } from "@/components/ui/card";
import { PageHeader } from "@/components/ui/page-header";
import { Stat } from "@/components/ui/stat";
import { EmptyState, ErrorState, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";
import { Button } from "@/components/ui/button";

const stateOrder: JobState[] = ["RUNNING", "LEASED", "QUEUED", "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "INFRA_ERROR"];

function humanAction(action: string) {
  return action.replace(/[._]/g, " ");
}

export default function OverviewPage() {
  const { organization } = useSession();
  const overview = useOverview();

  if (overview.isPending) return <Spinner />;
  if (overview.isError) return <ErrorState error={overview.error} onRetry={() => overview.refetch()} />;
  const o = overview.data;
  const totalJobs = Object.values(o.jobsByState).reduce((a, b) => a + b, 0);

  return (
    <>
      <PageHeader title="Overview" description={organization?.organizationName} />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Projects" value={formatNumber(o.projectCount)} detail={`${formatNumber(o.snapshotCount)} source snapshots`} />
        <Stat label="Test runs" value={formatNumber(totalJobs)} detail={`${o.jobsByState.FAILED ?? 0} failed · ${o.jobsByState.SUCCEEDED ?? 0} passed`} />
        <Stat label="Test cases executed" value={formatNumber(o.testTotals.passed + o.testTotals.failed + o.testTotals.errored + o.testTotals.skipped)} detail={`${formatNumber(o.testTotals.failed)} failing results`} />
        <Stat label="Active jobs" value={`${o.activeJobs} / ${o.activeJobLimit}`} detail="Concurrent job quota" />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title="Recent test runs" actions={<Link href="/test-runs" className="text-xs text-fg-2 hover:text-fg">View all</Link>} />
          {o.recentJobs.length === 0 ? (
            <EmptyState
              icon={<FolderGit2 className="size-5" />}
              title="No test runs yet"
              description="Create a project, import a source snapshot and start a test run. Results appear here as they finish."
              action={<Link href="/projects"><Button variant="primary">Go to projects</Button></Link>}
            />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Project</Th>
                  <Th>Status</Th>
                  <Th>Tests</Th>
                  <Th className="text-right">Started</Th>
                </tr>
              </thead>
              <tbody>
                {o.recentJobs.map((j) => (
                  <Tr key={j.id}>
                    <Td>
                      <Link href={`/test-runs/${j.id}`} className="font-medium text-fg hover:underline">
                        {j.projectName}
                      </Link>
                    </Td>
                    <Td><JobStateBadge state={j.state as JobState} /></Td>
                    <Td><TestCounts tests={j.summary?.tests as Record<string, unknown> | undefined} /></Td>
                    <Td className="text-right text-xs">{formatRelative(j.createdAt)}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <div className="space-y-6">
          <Card>
            <CardHeader title="Jobs by state" />
            <ul className="divide-y divide-border">
              {stateOrder.filter((s) => (o.jobsByState[s] ?? 0) > 0).map((s) => (
                <li key={s} className="flex items-center justify-between px-4 py-2">
                  <JobStateBadge state={s} />
                  <span className="tabular text-[13px] text-fg-2">{o.jobsByState[s]}</span>
                </li>
              ))}
              {totalJobs === 0 && <li className="px-4 py-3 text-[13px] text-fg-3">Nothing has run yet.</li>}
            </ul>
          </Card>
          <Card>
            <CardHeader title="Recent activity" />
            <ul className="divide-y divide-border">
              {o.recentActivity.map((a, i) => (
                <li key={i} className="px-4 py-2">
                  <p className="text-[13px] capitalize text-fg">{humanAction(a.action)}</p>
                  <p className="text-xs text-fg-3">
                    {a.actorType.toLowerCase().replace("_", " ")} · {formatRelative(a.occurredAt)}
                    {a.outcome !== "SUCCESS" && <span className="ml-1 text-danger">· {a.outcome.toLowerCase()}</span>}
                  </p>
                </li>
              ))}
              {o.recentActivity.length === 0 && <li className="px-4 py-3 text-[13px] text-fg-3">No activity recorded.</li>}
            </ul>
          </Card>
        </div>
      </div>
    </>
  );
}
