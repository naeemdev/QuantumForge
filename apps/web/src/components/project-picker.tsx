"use client";

import { useEffect } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useProjects } from "@/lib/queries";
import { Select } from "./ui/input";

/** Project selector synced with ?project=; defaults to the most recently updated project. */
export function useSelectedProject(): { projectId: string | undefined; projects: ReturnType<typeof useProjects> } {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const projects = useProjects();
  const requested = params.get("project") ?? undefined;
  const fallback = projects.data?.items[0]?.id;
  const projectId = requested ?? fallback;

  useEffect(() => {
    if (!requested && fallback) {
      const next = new URLSearchParams(params.toString());
      next.set("project", fallback);
      router.replace(`${pathname}?${next.toString()}`);
    }
  }, [requested, fallback, params, pathname, router]);

  return { projectId, projects };
}

export function ProjectPicker({ projectId, projects }: { projectId: string | undefined; projects: ReturnType<typeof useProjects> }) {
  const router = useRouter();
  const pathname = usePathname();
  return (
    <Select
      value={projectId ?? ""}
      onChange={(e) => router.replace(`${pathname}?project=${e.target.value}`)}
      className="w-64"
      aria-label="Project"
      disabled={!projects.data?.items.length}
    >
      {projects.data?.items.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
    </Select>
  );
}
