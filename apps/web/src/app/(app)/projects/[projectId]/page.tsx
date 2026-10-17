"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { FileCode2, GitBranch, Play, Plus, ScanSearch, Upload } from "lucide-react";
import type { CodeRepository, LanguageStat, Snapshot, SnapshotImportResult } from "@quantumforge/contracts";
import { api, call, uploadWithProgress } from "@/lib/api";
import { useFindingCounts, useJobs, useProject, useRepositories, useRevisions, useSnapshotLanguages, useSubmitAnalysis, useSubmitTestRun } from "@/lib/queries";
import { useSession } from "@/lib/session";
import { formatBytes, formatDuration, formatRelative, shortDigest } from "@/lib/format";
import { JobStateBadge, TestCounts } from "@/components/job-state";
import { EvidenceNote, SeverityBadge } from "@/components/findings";
import { SEVERITIES } from "@quantumforge/contracts";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardHeader } from "@/components/ui/card";
import { CopyButton } from "@/components/ui/copy";
import { Dialog } from "@/components/ui/dialog";
import { Field, Input, Select } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { EmptyState, ErrorState, InlineError, Spinner } from "@/components/ui/states";
import { Table, Td, Th, Tr } from "@/components/ui/table";

function AddRepositoryDialog({ projectId, open, onClose }: { projectId: string; open: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [sourceType, setSourceType] = useState<"UPLOAD" | "GIT">("UPLOAD");
  const [remoteUrl, setRemoteUrl] = useState("");
  const [branch, setBranch] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await call(
        api.POST("/api/v1/projects/{id}/repositories", {
          params: { path: { id: projectId } },
          body: { name, sourceType, remoteUrl: sourceType === "GIT" ? remoteUrl : undefined, defaultBranch: sourceType === "GIT" && branch ? branch : undefined },
        }),
      );
      await qc.invalidateQueries({ queryKey: ["repositories", projectId] });
      onClose();
      setName("");
      setRemoteUrl("");
      setBranch("");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} title="Add repository" description="Source is imported as immutable snapshots. Repository hooks are never executed.">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Name" htmlFor="r-name">
          <Input id="r-name" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} placeholder="payments-service" />
        </Field>
        <Field label="Source" htmlFor="r-type">
          <Select id="r-type" value={sourceType} onChange={(e) => setSourceType(e.target.value as "UPLOAD" | "GIT")}>
            <option value="UPLOAD">Archive upload (.zip, .tar.gz)</option>
            <option value="GIT">Public Git repository (HTTPS)</option>
          </Select>
        </Field>
        {sourceType === "GIT" && (
          <>
            <Field label="Repository URL" htmlFor="r-url" hint="HTTPS only, on an allowed host such as github.com. Credentials in URLs are rejected.">
              <Input id="r-url" required type="url" value={remoteUrl} onChange={(e) => setRemoteUrl(e.target.value)} placeholder="https://github.com/org/repo" />
            </Field>
            <Field label="Default branch" htmlFor="r-branch" hint="Optional. Uses the remote's default branch when empty.">
              <Input id="r-branch" value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="main" />
            </Field>
          </>
        )}
        <InlineError error={error} />
        <div className="flex justify-end gap-2">
          <Button type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" loading={busy}>Add repository</Button>
        </div>
      </form>
    </Dialog>
  );
}

function RepositoryActions({ repo, projectId, canImport }: { repo: CodeRepository; projectId: string; canImport: boolean }) {
  const qc = useQueryClient();
  const [progress, setProgress] = useState<number | null>(null);
  const [result, setResult] = useState<SnapshotImportResult | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function done(r: SnapshotImportResult) {
    setResult(r);
    await qc.invalidateQueries({ queryKey: ["revisions", projectId] });
  }

  async function upload(file: File) {
    setError(null);
    setResult(null);
    setProgress(0);
    const form = new FormData();
    form.append("archive", file);
    try {
      await done(await uploadWithProgress<SnapshotImportResult>(`/api/v1/repositories/${repo.id}/snapshots`, form, setProgress));
    } catch (err) {
      setError(err);
    } finally {
      setProgress(null);
    }
  }

  async function gitImport() {
    setError(null);
    setResult(null);
    setProgress(0);
    try {
      await done(await call(api.POST("/api/v1/repositories/{id}/git-imports", { params: { path: { id: repo.id } }, body: {} })));
    } catch (err) {
      setError(err);
    } finally {
      setProgress(null);
    }
  }

  return (
    <div className="space-y-2">
      {canImport && (
        repo.sourceType === "UPLOAD" ? (
          <label className="inline-flex h-7 cursor-pointer items-center gap-1.5 rounded-md border border-border bg-surface px-2.5 text-[13px] font-medium hover:bg-surface-hover">
            <Upload className="size-3.5" />
            {progress != null ? `Uploading ${Math.round(progress * 100)}%` : "Upload archive"}
            <input
              type="file"
              accept=".zip,.tar.gz,.tgz,application/zip,application/gzip"
              className="sr-only"
              disabled={progress != null}
              onChange={(e) => {
                const f = e.target.files?.[0];
                e.target.value = "";
                if (f) void upload(f);
              }}
            />
          </label>
        ) : (
          <Button size="sm" onClick={() => void gitImport()} loading={progress != null}>
            <GitBranch className="size-3.5" /> Import latest
          </Button>
        )
      )}
      {result && (
        <p className="text-xs text-fg-2">
          {result.created ? "Imported" : "Already imported"} snapshot <span className="font-mono">{shortDigest(result.snapshot.contentDigest)}</span> · {result.snapshot.fileCount} files
          {result.skipped.length > 0 && <span className="text-warning"> · {result.skipped.length} skipped</span>}
        </p>
      )}
      <InlineError error={error} />
    </div>
  );
}

function LanguageBar({ stats }: { stats: LanguageStat[] }) {
  const total = stats.reduce((a, s) => a + s.lineCount, 0);
  if (total === 0) return <p className="text-[13px] text-fg-3">No recognised source files.</p>;
  const shades = ["var(--accent)", "var(--text-2)", "var(--success)", "var(--warning)", "var(--text-3)", "var(--danger)"];
  return (
    <div>
      <div className="flex h-2 overflow-hidden rounded-full bg-surface-2">
        {stats.map((s, i) => (
          <div key={s.language} style={{ width: `${(s.lineCount / total) * 100}%`, background: shades[i % shades.length] }} title={s.language} />
        ))}
      </div>
      <ul className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1">
        {stats.map((s, i) => (
          <li key={s.language} className="flex items-center justify-between gap-2 text-xs">
            <span className="flex items-center gap-1.5 text-fg-2">
              <span className="size-2 rounded-full" style={{ background: shades[i % shades.length] }} aria-hidden />
              {s.language}
            </span>
            <span className="tabular text-fg-3">{((s.lineCount / total) * 100).toFixed(1)}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function RunTestsDialog({ projectId, snapshots, open, onClose }: { projectId: string; snapshots: Snapshot[]; open: boolean; onClose: () => void }) {
  const router = useRouter();
  const submit = useSubmitTestRun(projectId);
  const [snapshotId, setSnapshotId] = useState<string>("");
  const [timeout, setTimeoutValue] = useState("300");
  const [patterns, setPatterns] = useState("");
  // One key per dialog opening: double-clicks and network retries cannot create two jobs.
  const idempotencyKey = useMemo(() => (open ? crypto.randomUUID() : ""), [open]);

  async function run(e: React.FormEvent) {
    e.preventDefault();
    const job = await submit.mutateAsync({
      idempotencyKey,
      snapshotId: snapshotId || snapshots[0]?.id,
      timeoutSeconds: Number(timeout) || undefined,
      testPatterns: patterns.split(/[\s,]+/).filter(Boolean),
    });
    router.push(`/test-runs/${job.id}`);
  }

  return (
    <Dialog open={open} onClose={onClose} title="Run tests" description="The snapshot is executed by an isolated worker. Nothing runs on the API server.">
      <form onSubmit={run} className="space-y-4">
        <Field label="Snapshot" htmlFor="t-snap">
          <Select id="t-snap" value={snapshotId} onChange={(e) => setSnapshotId(e.target.value)}>
            {snapshots.map((s, i) => (
              <option key={s.id} value={i === 0 ? "" : s.id}>
                {shortDigest(s.contentDigest)} · {s.gitCommit ? s.gitCommit.slice(0, 10) : s.originalFilename ?? s.origin.toLowerCase()} · {formatRelative(s.createdAt)}
                {i === 0 ? " (latest)" : ""}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Runner" htmlFor="t-preset" hint="Node.js test runner with TypeScript type stripping. More toolchains are added as presets.">
          <Select id="t-preset" disabled value="NODE_TEST">
            <option value="NODE_TEST">node:test (TypeScript / JavaScript)</option>
          </Select>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Time limit (seconds)" htmlFor="t-timeout">
            <Input id="t-timeout" type="number" min={1} max={1800} value={timeout} onChange={(e) => setTimeoutValue(e.target.value)} />
          </Field>
          <Field label="Test files" htmlFor="t-patterns" hint="Globs; defaults to **/*.test.{ts,js,mjs}">
            <Input id="t-patterns" value={patterns} onChange={(e) => setPatterns(e.target.value)} placeholder="tests/**/*.test.ts" className="font-mono" />
          </Field>
        </div>
        <InlineError error={submit.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" loading={submit.isPending}>
            <Play className="size-3.5" /> Queue run
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

export default function ProjectPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const { can } = useSession();
  const project = useProject(projectId);
  const repositories = useRepositories(projectId);
  const revisions = useRevisions(projectId);
  const latest = revisions.data?.items[0];
  const languages = useSnapshotLanguages(latest?.id);
  const jobs = useJobs({ projectId, type: "TEST_EXECUTION" });
  const findingCounts = useFindingCounts(projectId);
  const analyse = useSubmitAnalysis(projectId);
  const router = useRouter();
  const [addingRepo, setAddingRepo] = useState(false);
  const [running, setRunning] = useState(false);

  if (project.isPending) return <Spinner />;
  if (project.isError) return <ErrorState error={project.error} />;
  const p = project.data;
  const repoName = (id: string) => repositories.data?.find((r) => r.id === id)?.name ?? "—";

  return (
    <>
      <PageHeader
        breadcrumbs={[{ label: "Projects", href: "/projects" }, { label: p.name }]}
        title={p.name}
        description={p.description ?? undefined}
        actions={
          <>
            {latest && (
              <Link href={`/projects/${projectId}/source?snapshot=${latest.id}`}>
                <Button><FileCode2 className="size-4" /> Browse source</Button>
              </Link>
            )}
            {can("job.submit") && (
              <Button
                disabled={!latest}
                loading={analyse.isPending}
                onClick={async () => {
                  const job = await analyse.mutateAsync({ idempotencyKey: crypto.randomUUID(), snapshotId: latest?.id });
                  router.push(`/test-runs/${job.id}`);
                }}
              >
                <ScanSearch className="size-4" /> Analyse
              </Button>
            )}
            {can("job.submit") && (
              <Button variant="primary" disabled={!latest} onClick={() => setRunning(true)} title={latest ? undefined : "Import a snapshot first"}>
                <Play className="size-4" /> Run tests
              </Button>
            )}
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card>
            <CardHeader
              title="Repositories"
              actions={can("project.update") && <Button size="sm" onClick={() => setAddingRepo(true)}><Plus className="size-3.5" /> Add</Button>}
            />
            {repositories.isPending ? <Spinner /> : repositories.isError ? (
              <div className="p-4"><ErrorState error={repositories.error} /></div>
            ) : repositories.data.length === 0 ? (
              <EmptyState title="No repositories" description="Add a repository to upload an archive or import a public Git repository." />
            ) : (
              <ul className="divide-y divide-border">
                {repositories.data.map((r) => (
                  <li key={r.id} className="flex flex-wrap items-start justify-between gap-4 px-4 py-3">
                    <div className="min-w-0">
                      <p className="flex items-center gap-2 text-[13px] font-medium">
                        {r.name}
                        <Badge>{r.sourceType === "GIT" ? "Git" : "Upload"}</Badge>
                      </p>
                      {r.remoteUrl && <p className="mt-0.5 truncate font-mono text-xs text-fg-3">{r.remoteUrl}{r.defaultBranch ? ` @ ${r.defaultBranch}` : ""}</p>}
                    </div>
                    <RepositoryActions repo={r} projectId={projectId} canImport={can("source.import")} />
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card>
            <CardHeader title="Snapshots" description="Immutable, content-addressed source revisions. Identical content is stored once." />
            {revisions.isPending ? <Spinner /> : revisions.isError ? <div className="p-4"><ErrorState error={revisions.error} /></div> : revisions.data.items.length === 0 ? (
              <EmptyState title="No snapshots yet" description="Upload an archive or import from Git to create the first snapshot." />
            ) : (
              <Table>
                <thead>
                  <tr>
                    <Th>Digest</Th>
                    <Th>Repository</Th>
                    <Th>Origin</Th>
                    <Th className="text-right">Files</Th>
                    <Th className="text-right">Size</Th>
                    <Th className="text-right">Imported</Th>
                  </tr>
                </thead>
                <tbody>
                  {revisions.data.items.map((s) => (
                    <Tr key={s.id}>
                      <Td>
                        <Link href={`/projects/${projectId}/source?snapshot=${s.id}`} className="font-mono text-xs text-fg hover:underline">{shortDigest(s.contentDigest)}</Link>
                        <CopyButton value={s.contentDigest} label="" />
                      </Td>
                      <Td>{repoName(s.repositoryId)}</Td>
                      <Td className="text-xs">
                        {s.origin === "GIT" ? <span className="font-mono">{s.gitRef ?? "HEAD"} · {s.gitCommit?.slice(0, 10)}</span> : s.originalFilename ?? "upload"}
                      </Td>
                      <Td className="tabular text-right">{s.fileCount}</Td>
                      <Td className="tabular text-right">{formatBytes(s.totalBytes)}</Td>
                      <Td className="text-right text-xs">{formatRelative(s.createdAt)}</Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>

          <Card>
            <CardHeader title="Test runs" actions={<Link href={`/test-runs?project=${projectId}`} className="text-xs text-fg-2 hover:text-fg">View all</Link>} />
            {jobs.isPending ? <Spinner /> : jobs.isError ? <div className="p-4"><ErrorState error={jobs.error} /></div> : jobs.data.items.length === 0 ? (
              <EmptyState title="No test runs" description="Queue a run to execute this project's tests in an isolated worker." />
            ) : (
              <Table>
                <thead>
                  <tr>
                    <Th>Run</Th>
                    <Th>Status</Th>
                    <Th>Tests</Th>
                    <Th className="text-right">Duration</Th>
                    <Th className="text-right">Queued</Th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.data.items.slice(0, 10).map((j) => (
                    <Tr key={j.id}>
                      <Td><Link href={`/test-runs/${j.id}`} className="font-mono text-xs text-fg hover:underline">{j.id.slice(-8)}</Link></Td>
                      <Td><JobStateBadge state={j.state} /></Td>
                      <Td><TestCounts tests={j.resultSummary?.tests as Record<string, unknown> | undefined} /></Td>
                      <Td className="tabular text-right text-xs">{formatDuration(j.resultSummary?.durationMs as number | undefined)}</Td>
                      <Td className="text-right text-xs">{formatRelative(j.queuedAt)}</Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader title="Language composition" description={latest ? `Latest snapshot ${shortDigest(latest.contentDigest)}` : undefined} />
            <CardBody>
              {!latest ? <p className="text-[13px] text-fg-3">Import a snapshot to see languages.</p> : languages.isPending ? <Spinner /> : languages.isError ? <ErrorState error={languages.error} /> : <LanguageBar stats={languages.data} />}
              <p className="mt-3 text-[11px] text-fg-3">Classified by file name. This does not imply the files were parsed.</p>
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Active findings" actions={<Link href={`/findings?project=${projectId}`} className="text-xs text-fg-2 hover:text-fg">Explore</Link>} />
            <CardBody>
              {findingCounts.data && Object.keys(findingCounts.data.bySeverity).length > 0 ? (
                <ul className="space-y-1.5">
                  {SEVERITIES.filter((s) => findingCounts.data.bySeverity[s]).map((s) => (
                    <li key={s} className="flex items-center justify-between text-[13px]"><SeverityBadge severity={s} /><span className="tabular text-fg-2">{findingCounts.data.bySeverity[s]}</span></li>
                  ))}
                </ul>
              ) : <p className="text-[13px] text-fg-3">No active findings. Run an analysis to check the latest snapshot.</p>}
              <EvidenceNote className="mt-3" />
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Details" />
            <dl className="divide-y divide-border text-[13px]">
              {[
                ["Slug", <span key="s" className="font-mono text-xs">{p.slug}</span>],
                ["Status", p.status === "ACTIVE" ? "Active" : "Archived"],
                ["Created", formatRelative(p.createdAt)],
                ["Updated", formatRelative(p.updatedAt)],
              ].map(([k, v]) => (
                <div key={String(k)} className="flex justify-between px-4 py-2">
                  <dt className="text-fg-3">{k}</dt>
                  <dd className="text-fg-2">{v}</dd>
                </div>
              ))}
            </dl>
          </Card>
        </div>
      </div>

      <AddRepositoryDialog projectId={projectId} open={addingRepo} onClose={() => setAddingRepo(false)} />
      <InlineError error={analyse.error} />
      {revisions.data && <RunTestsDialog projectId={projectId} snapshots={revisions.data.items} open={running} onClose={() => setRunning(false)} />}
    </>
  );
}
