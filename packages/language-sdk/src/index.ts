/**
 * Language adapter contract.
 *
 * An adapter runs inside an execution sandbox, reads a snapshot directory and
 * writes one AnalysisReport as JSON. The control plane validates and ingests the
 * report; it never trusts it further than its schema. Adapters must declare
 * what they actually do: a capability marked "none" or "syntactic" tells the UI
 * and the reports not to present a result as more than it is.
 */

export const REPORT_SCHEMA_VERSION = 1;

/** How deep an adapter goes for each capability. */
export type CapabilityLevel =
  | "none"
  /** Derived from the syntax tree only. */
  | "syntactic"
  /** Uses name resolution / a type checker within the analysed files. */
  | "semantic";

export interface AdapterCapabilities {
  parse: CapabilityLevel;
  symbols: CapabilityLevel;
  references: CapabilityLevel;
  imports: CapabilityLevel;
  callGraph: CapabilityLevel;
  controlFlow: CapabilityLevel;
  dataFlow: CapabilityLevel;
  diagnostics: CapabilityLevel;
}

export interface AdapterInfo {
  id: string;
  version: string;
  languages: string[];
  capabilities: AdapterCapabilities;
  /** Toolchain used, e.g. "typescript 5.9.3". */
  toolchain: string;
  /** Plain-language limitations shown next to results. */
  limitations: string[];
}

export interface SourceRange {
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
}

export interface FileReport {
  path: string;
  language: string;
  parsed: boolean;
  lineCount: number;
  diagnostics: { range: SourceRange; message: string; category: "error" | "warning" }[];
  skippedReason?: string;
}

export type SymbolKind = "function" | "class" | "method" | "interface" | "type" | "enum" | "variable";

export interface SymbolReport {
  /** Stable within a snapshot: path#Container.name (with ~n for overloads). */
  key: string;
  kind: SymbolKind;
  name: string;
  container: string | null;
  path: string;
  range: SourceRange;
  exported: boolean;
  /** Cyclomatic complexity for function-like symbols. */
  complexity: number | null;
  signature: string | null;
}

export interface ImportReport {
  from: string;
  specifier: string;
  /** Resolved file inside the snapshot, or null for external/unresolved modules. */
  to: string | null;
  external: boolean;
  /** Package name for external modules. */
  packageName: string | null;
  line: number;
}

export interface CallReport {
  from: string;
  to: string;
  path: string;
  line: number;
}

export type Severity = "critical" | "high" | "medium" | "low" | "info";
export type Confidence = "high" | "medium" | "low";

export interface RuleReport {
  id: string;
  version: number;
  name: string;
  summary: string;
  description: string;
  remediation: string;
  category: string;
  severity: Severity;
  confidence: Confidence;
  languages: string[];
}

export interface FindingReport {
  ruleId: string;
  ruleVersion: number;
  severity: Severity;
  confidence: Confidence;
  message: string;
  path: string;
  range: SourceRange;
  snippet: string;
  symbolKey: string | null;
  /** Survives unrelated edits and line shifts; see the adapter's fingerprint rules. */
  fingerprint: string;
}

export interface AnalysisReport {
  schemaVersion: typeof REPORT_SCHEMA_VERSION;
  adapter: AdapterInfo;
  files: FileReport[];
  symbols: SymbolReport[];
  imports: ImportReport[];
  calls: CallReport[];
  rules: RuleReport[];
  findings: FindingReport[];
  stats: { filesSeen: number; filesAnalysed: number; durationMs: number; truncated: boolean };
}
