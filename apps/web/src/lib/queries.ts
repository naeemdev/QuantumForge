"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { TERMINAL_JOB_STATES, type JobState } from "@quantumforge/contracts";
import { api, call } from "./api";
import { useOrgId } from "./session";

// Query hooks: one place that knows endpoints and cache keys. Components only
// render what these return.

export function useOverview() {
  const orgId = useOrgId();
  return useQuery({
    queryKey: ["overview", orgId],
    queryFn: () => call(api.GET("/api/v1/organizations/{organizationId}/overview", { params: { path: { organizationId: orgId } } })),
    refetchInterval: 10_000,
  });
}

export function useProjects(q?: string, page = 0) {
  const orgId = useOrgId();
  return useQuery({
    queryKey: ["projects", orgId, q ?? "", page],
    queryFn: () => call(api.GET("/api/v1/projects", { params: { query: { organizationId: orgId, q, page, size: 50 } } })),
  });
}

export function useProject(projectId: string) {
  return useQuery({
    queryKey: ["project", projectId],
    queryFn: () => call(api.GET("/api/v1/projects/{id}", { params: { path: { id: projectId } } })),
  });
}

export function useRepositories(projectId: string) {
  return useQuery({
    queryKey: ["repositories", projectId],
    queryFn: () => call(api.GET("/api/v1/projects/{id}/repositories", { params: { path: { id: projectId } } })),
  });
}

export function useRevisions(projectId: string) {
  return useQuery({
    queryKey: ["revisions", projectId],
    queryFn: () => call(api.GET("/api/v1/projects/{id}/revisions", { params: { path: { id: projectId }, query: { size: 50 } } })),
  });
}

export function useSnapshotFiles(snapshotId: string | undefined, prefix?: string) {
  return useQuery({
    queryKey: ["files", snapshotId, prefix ?? ""],
    queryFn: () => call(api.GET("/api/v1/snapshots/{id}/files", { params: { path: { id: snapshotId! }, query: { prefix, size: 200 } } })),
    enabled: snapshotId != null,
  });
}

export function useSnapshotLanguages(snapshotId: string | undefined) {
  return useQuery({
    queryKey: ["languages", snapshotId],
    queryFn: () => call(api.GET("/api/v1/snapshots/{id}/languages", { params: { path: { id: snapshotId! } } })),
    enabled: snapshotId != null,
  });
}

export function useFileContent(snapshotId: string | undefined, path: string | undefined) {
  return useQuery({
    queryKey: ["content", snapshotId, path],
    queryFn: () => call(api.GET("/api/v1/snapshots/{id}/content", { params: { path: { id: snapshotId! }, query: { path: path! } } })),
    enabled: snapshotId != null && path != null,
    retry: false,
  });
}

export function useJobs(filter: { projectId?: string; states?: JobState[]; page?: number } = {}) {
  const orgId = useOrgId();
  return useQuery({
    queryKey: ["jobs", orgId, filter],
    queryFn: () =>
      call(api.GET("/api/v1/jobs", { params: { query: { organizationId: orgId, projectId: filter.projectId, state: filter.states, page: filter.page ?? 0, size: 50 } } })),
    refetchInterval: (query) => (query.state.data?.items.some((j) => !TERMINAL_JOB_STATES.includes(j.state)) ? 3000 : 15_000),
  });
}

export function useJob(jobId: string) {
  return useQuery({
    queryKey: ["job", jobId],
    queryFn: () => call(api.GET("/api/v1/jobs/{id}", { params: { path: { id: jobId } } })),
    refetchInterval: (query) => (query.state.data && TERMINAL_JOB_STATES.includes(query.state.data.job.state) ? false : 2000),
  });
}

export function useJobTestResults(jobId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["job-tests", jobId],
    queryFn: () => call(api.GET("/api/v1/jobs/{id}/test-results", { params: { path: { id: jobId } } })),
    enabled,
  });
}

export function useJobArtifacts(jobId: string, refreshKey: unknown) {
  return useQuery({
    queryKey: ["job-artifacts", jobId, refreshKey],
    queryFn: () => call(api.GET("/api/v1/jobs/{id}/artifacts", { params: { path: { id: jobId } } })),
  });
}

export function useSubmitTestRun(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { snapshotId?: string; idempotencyKey: string; timeoutSeconds?: number; testPatterns?: string[] }) =>
      call(
        api.POST("/api/v1/projects/{projectId}/test-runs", {
          params: { path: { projectId }, header: { "Idempotency-Key": body.idempotencyKey } },
          body: {
            snapshotId: body.snapshotId,
            priority: 0,
            config: {
              preset: "NODE_TEST",
              testPatterns: body.testPatterns ?? [],
              timeoutSeconds: body.timeoutSeconds,
              memoryMb: 512,
              cpuMillis: 1000,
              maxOutputBytes: 1048576,
            },
          },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["jobs"] });
      void qc.invalidateQueries({ queryKey: ["overview"] });
    },
  });
}

export function useCancelJob(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST("/api/v1/jobs/{id}/cancel", { params: { path: { id: jobId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["job", jobId] }),
  });
}

export function useMembers() {
  const orgId = useOrgId();
  return useQuery({
    queryKey: ["members", orgId],
    queryFn: () => call(api.GET("/api/v1/organizations/{id}/members", { params: { path: { id: orgId }, query: { size: 200 } } })),
  });
}

export function useInvitations(enabled: boolean) {
  const orgId = useOrgId();
  return useQuery({
    queryKey: ["invitations", orgId],
    queryFn: () => call(api.GET("/api/v1/organizations/{id}/invitations", { params: { path: { id: orgId } } })),
    enabled,
  });
}

export function useRoles() {
  return useQuery({ queryKey: ["roles"], queryFn: () => call(api.GET("/api/v1/roles")), staleTime: Infinity });
}

export function useApiTokens() {
  const orgId = useOrgId();
  return useQuery({
    queryKey: ["api-tokens", orgId],
    queryFn: () => call(api.GET("/api/v1/organizations/{id}/api-tokens", { params: { path: { id: orgId } } })),
  });
}

export function useAuditEvents(page: number, enabled: boolean) {
  const orgId = useOrgId();
  return useQuery({
    queryKey: ["audit", orgId, page],
    queryFn: () =>
      call(api.GET("/api/v1/organizations/{organizationId}/audit-events", { params: { path: { organizationId: orgId }, query: { page, size: 50 } } })),
    enabled,
  });
}
