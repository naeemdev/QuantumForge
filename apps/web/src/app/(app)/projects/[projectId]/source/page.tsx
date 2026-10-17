"use client";

import { Suspense, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, File, Folder } from "lucide-react";
import type { SourceFile } from "@quantumforge/contracts";
import { api, call } from "@/lib/api";
import Link from "next/link";
import { useAnnotations, useFileContent, useProject } from "@/lib/queries";
import { CodeViewer } from "@/components/code-viewer";
import { SeverityBadge } from "@/components/findings";
import { formatBytes, shortDigest } from "@/lib/format";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { PageHeader } from "@/components/ui/page-header";
import { EmptyState, ErrorState, Spinner } from "@/components/ui/states";
import { cn } from "@/components/ui/cn";

interface TreeNode {
  name: string;
  path: string;
  children: Map<string, TreeNode>;
  file?: SourceFile;
}

function buildTree(files: SourceFile[]): TreeNode {
  const root: TreeNode = { name: "", path: "", children: new Map() };
  for (const f of files) {
    let node = root;
    const parts = f.path.split("/");
    parts.forEach((part, i) => {
      const path = parts.slice(0, i + 1).join("/");
      let child = node.children.get(part);
      if (!child) {
        child = { name: part, path, children: new Map() };
        node.children.set(part, child);
      }
      if (i === parts.length - 1) child.file = f;
      node = child;
    });
  }
  return root;
}

function sortedChildren(node: TreeNode): TreeNode[] {
  return [...node.children.values()].sort((a, b) => {
    const aDir = a.file ? 1 : 0;
    const bDir = b.file ? 1 : 0;
    return aDir - bDir || a.name.localeCompare(b.name);
  });
}

function TreeView({ node, depth, selected, onSelect, open, toggle }: {
  node: TreeNode;
  depth: number;
  selected?: string;
  onSelect: (path: string) => void;
  open: Set<string>;
  toggle: (path: string) => void;
}) {
  return (
    <ul role={depth === 0 ? "tree" : "group"}>
      {sortedChildren(node).map((child) => {
        const isDir = !child.file;
        const expanded = open.has(child.path);
        return (
          <li key={child.path} role="treeitem" aria-expanded={isDir ? expanded : undefined} aria-selected={selected === child.path}>
            <button
              onClick={() => (isDir ? toggle(child.path) : onSelect(child.path))}
              className={cn(
                "flex h-7 w-full items-center gap-1.5 rounded pr-2 text-left text-[13px] hover:bg-surface-hover",
                selected === child.path ? "bg-accent-soft text-fg" : "text-fg-2",
              )}
              style={{ paddingLeft: depth * 12 + 6 }}
            >
              {isDir ? (expanded ? <ChevronDown className="size-3.5 shrink-0" /> : <ChevronRight className="size-3.5 shrink-0" />) : <span className="w-3.5 shrink-0" />}
              {isDir ? <Folder className="size-3.5 shrink-0 text-fg-3" /> : <File className="size-3.5 shrink-0 text-fg-3" />}
              <span className="truncate">{child.name}</span>
            </button>
            {isDir && expanded && <TreeView node={child} depth={depth + 1} selected={selected} onSelect={onSelect} open={open} toggle={toggle} />}
          </li>
        );
      })}
    </ul>
  );
}

function CodeView({ projectId, snapshotId, path, line }: { projectId: string; snapshotId: string; path: string; line?: number }) {
  const content = useFileContent(snapshotId, path);
  const annotations = useAnnotations(projectId, snapshotId, path);
  const router = useRouter();
  if (content.isPending) return <Spinner />;
  if (content.isError) return <div className="p-4"><ErrorState error={content.error} /></div>;
  const findings = annotations.data?.findings ?? [];
  const symbols = annotations.data?.symbols ?? [];
  const lines = content.data.content.split("\n").length;
  const goTo = (n: number) => router.replace(`/projects/${projectId}/source?snapshot=${snapshotId}&path=${encodeURIComponent(path)}&line=${n}`);
  return (
    <div>
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <p className="truncate font-mono text-xs text-fg">{path}</p>
        <p className="shrink-0 text-xs text-fg-3">
          {content.data.language ?? "Plain text"} · {lines} lines · {formatBytes(content.data.sizeBytes)}
          {annotations.data?.indexId ? ` · ${findings.length} ${findings.length === 1 ? "finding" : "findings"}` : " · not analysed"}
        </p>
      </div>
      <div className="grid grid-cols-[1fr_15rem]">
        <CodeViewer
          value={content.data.content}
          language={content.data.language}
          path={`${snapshotId}/${path}`}
          revealLine={line}
          markers={findings.map((f) => ({ startLine: f.startLine, startColumn: f.startColumn, endLine: f.endLine, endColumn: f.endColumn, message: `${f.ruleId}: ${f.message}`, severity: f.severity }))}
        />
        <div className="max-h-[70vh] overflow-auto border-l border-border">
          {findings.length > 0 && (
            <div className="border-b border-border p-2">
              <p className="px-1 pb-1 text-[11px] font-medium uppercase tracking-wide text-fg-3">Findings</p>
              {findings.map((f) => (
                <Link key={f.findingId} href={`/findings/${f.findingId}`} className="block rounded px-1.5 py-1 hover:bg-surface-hover">
                  <span className="flex items-center gap-1.5"><SeverityBadge severity={f.severity} /><span className="font-mono text-[11px] text-fg-3">L{f.startLine}</span></span>
                  <span className="mt-0.5 line-clamp-2 block text-xs text-fg-2">{f.message}</span>
                </Link>
              ))}
            </div>
          )}
          <div className="p-2">
            <p className="px-1 pb-1 text-[11px] font-medium uppercase tracking-wide text-fg-3">Outline</p>
            {symbols.length === 0 ? <p className="px-1 text-xs text-fg-3">{annotations.data?.indexId ? "No symbols." : "Analyse the project to see symbols."}</p> : symbols.map((s) => (
              <button key={s.key} onClick={() => goTo(s.startLine)} className="flex w-full items-center justify-between gap-2 rounded px-1.5 py-1 text-left hover:bg-surface-hover">
                <span className="truncate font-mono text-xs text-fg-2">{s.container ? `${s.container}.` : ""}{s.name}</span>
                <span className="shrink-0 text-[10px] uppercase text-fg-3">{s.kind}</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function SourceBrowser() {
  const { projectId } = useParams<{ projectId: string }>();
  const search = useSearchParams();
  const router = useRouter();
  const snapshotId = search.get("snapshot") ?? undefined;
  const selected = search.get("path") ?? undefined;
  const line = Number(search.get("line")) || undefined;
  const project = useProject(projectId);
  const [filter, setFilter] = useState("");
  const [open, setOpen] = useState<Set<string>>(new Set());

  const snapshot = useQuery({
    queryKey: ["snapshot", snapshotId],
    queryFn: () => call(api.GET("/api/v1/snapshots/{id}", { params: { path: { id: snapshotId! } } })),
    enabled: snapshotId != null,
  });

  // Snapshots are immutable, so the full listing can be cached indefinitely.
  const files = useQuery({
    queryKey: ["all-files", snapshotId],
    staleTime: Infinity,
    enabled: snapshotId != null,
    queryFn: async () => {
      const all: SourceFile[] = [];
      for (let page = 0; page < 50; page++) {
        const r = await call(api.GET("/api/v1/snapshots/{id}/files", { params: { path: { id: snapshotId! }, query: { page, size: 200 } } }));
        all.push(...r.items);
        if (all.length >= r.total) break;
      }
      return all;
    },
  });

  const visible = useMemo(() => {
    const list = files.data ?? [];
    const q = filter.trim().toLowerCase();
    return q ? list.filter((f) => f.path.toLowerCase().includes(q)) : list;
  }, [files.data, filter]);
  const tree = useMemo(() => buildTree(visible), [visible]);
  const effectiveOpen = useMemo(() => {
    if (!filter.trim()) return open;
    // While filtering, expand everything that matches.
    return new Set(visible.flatMap((f) => f.path.split("/").slice(0, -1).map((_, i, parts) => parts.slice(0, i + 1).join("/"))));
  }, [filter, open, visible]);

  const select = (path: string) => router.replace(`/projects/${projectId}/source?snapshot=${snapshotId}&path=${encodeURIComponent(path)}`);
  const toggle = (path: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });

  if (!snapshotId) return <EmptyState title="No snapshot selected" />;

  return (
    <>
      <PageHeader
        breadcrumbs={[{ label: "Projects", href: "/projects" }, { label: project.data?.name ?? "…", href: `/projects/${projectId}` }, { label: "Source" }]}
        title="Source"
        description={snapshot.data ? `Snapshot ${shortDigest(snapshot.data.contentDigest)} · ${snapshot.data.fileCount} files · read-only` : undefined}
      />
      <Card className="grid min-h-[60vh] grid-cols-[16rem_1fr] overflow-hidden">
        <div className="flex min-h-0 flex-col border-r border-border">
          <div className="border-b border-border p-2">
            <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter files" aria-label="Filter files" />
          </div>
          <div className="max-h-[70vh] overflow-auto p-1">
            {files.isPending ? <Spinner /> : files.isError ? <ErrorState error={files.error} /> : (
              <TreeView node={tree} depth={0} selected={selected} onSelect={select} open={effectiveOpen} toggle={toggle} />
            )}
          </div>
        </div>
        <div className="min-w-0">
          {selected ? <CodeView projectId={projectId} snapshotId={snapshotId} path={selected} line={line} /> : <EmptyState title="Select a file" description="Choose a file from the tree to view its contents." />}
        </div>
      </Card>
    </>
  );
}

export default function SourcePage() {
  return (
    <Suspense fallback={<Spinner />}>
      <SourceBrowser />
    </Suspense>
  );
}
