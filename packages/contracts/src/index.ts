// Generated types live in schema.ts (run `pnpm contracts:generate` after the API changes).
export type { paths, components, operations } from "./schema";

import type { components } from "./schema";

type Schemas = components["schemas"];

export type Job = Schemas["Job"];
export type JobState = Job["state"];
export type JobDetail = Schemas["JobDetail"];
export type JobAttempt = Schemas["JobAttempt"];
export type JobEvent = Schemas["JobEvent"];
export type JobArtifact = Schemas["JobArtifact"];
export type TestRunView = Schemas["TestRunView"];
export type TestCaseView = Schemas["TestCaseView"];
export type Project = Schemas["Project"];
export type CodeRepository = Schemas["CodeRepository"];
export type Snapshot = Schemas["Snapshot"];
export type SnapshotImportResult = Schemas["SnapshotImportResult"];
export type SourceFile = Schemas["SourceFile"];
export type FileContent = Schemas["FileContent"];
export type LanguageStat = Schemas["LanguageStat"];
export type OrganizationView = Schemas["OrganizationView"];
export type OrganizationOverview = Schemas["OrganizationOverview"];
export type Member = Schemas["Member"];
export type Invitation = Schemas["Invitation"];
export type ApiToken = Schemas["ApiToken"];
export type AuditEventView = Schemas["AuditEventView"];
export type MeResponse = Schemas["MeResponse"];
export type MembershipView = Schemas["MembershipView"];
export type RoleCatalog = Schemas["RoleCatalog"];
export type AuthConfigResponse = Schemas["AuthConfigResponse"];

export const TERMINAL_JOB_STATES: readonly JobState[] = ["SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "INFRA_ERROR"];

/** Error envelope returned by every endpoint on failure. */
export interface ApiErrorBody {
  error: {
    code: string;
    message: string;
    status: number;
    requestId: string | null;
    timestamp: string;
    details: Record<string, unknown>;
    fieldErrors: { field: string; message: string }[];
  };
}
