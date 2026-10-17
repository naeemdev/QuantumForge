import ts from "typescript";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import {
  REPORT_SCHEMA_VERSION,
  type AdapterInfo,
  type AnalysisReport,
  type CallReport,
  type FileReport,
  type FindingReport,
  type ImportReport,
} from "@quantumforge/language-sdk";
import { RULES } from "./rules/index.ts";
import { indexSymbols, type IndexedSymbols } from "./symbols.ts";
import { collectCalls, collectImports, owningSymbol } from "./graph.ts";
import { normalizeCode, rangeOf, sha256, snippetOf } from "./util.ts";

export const ADAPTER_VERSION = "0.1.0";

const SOURCE_EXTENSIONS = new Map([
  [".ts", "TypeScript"], [".tsx", "TypeScript"], [".mts", "TypeScript"], [".cts", "TypeScript"],
  [".js", "JavaScript"], [".jsx", "JavaScript"], [".mjs", "JavaScript"], [".cjs", "JavaScript"],
]);
const SKIPPED_DIRECTORIES = new Set(["node_modules", ".git", "dist", "build", "out", "coverage", ".next", "vendor"]);

export interface AnalyzeOptions {
  maxFiles: number;
  maxFileBytes: number;
}

export const DEFAULT_OPTIONS: AnalyzeOptions = { maxFiles: 5000, maxFileBytes: 1024 * 1024 };

export const ADAPTER: AdapterInfo = {
  id: "typescript",
  version: ADAPTER_VERSION,
  languages: ["TypeScript", "JavaScript"],
  capabilities: {
    parse: "semantic",
    symbols: "syntactic",
    references: "semantic",
    imports: "semantic",
    callGraph: "semantic",
    controlFlow: "syntactic",
    dataFlow: "none",
    diagnostics: "syntactic",
  },
  toolchain: `typescript ${ts.version}`,
  limitations: [
    "Files are type-checked together with fixed compiler options; the project's own tsconfig and installed dependencies are not used, so types from external packages resolve to 'any'.",
    "The call graph contains direct calls whose target can be resolved statically; calls through callbacks, dynamic dispatch or external packages are not included.",
    "Control flow is limited to cyclomatic complexity; no path-sensitive or data-flow analysis is performed.",
    "Only syntax errors are reported as diagnostics.",
    "Findings are suspected issues from pattern rules, not confirmed defects.",
  ],
};

function languageOf(path: string): string | undefined {
  if (path.endsWith(".d.ts")) return undefined;
  const dot = path.lastIndexOf(".");
  return dot < 0 ? undefined : SOURCE_EXTENSIONS.get(path.slice(dot).toLowerCase());
}

function listSourceFiles(root: string, options: AnalyzeOptions): { files: string[]; seen: number; truncated: boolean } {
  const files: string[] = [];
  let seen = 0;
  let truncated = false;
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.isSymbolicLink()) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!SKIPPED_DIRECTORIES.has(entry.name)) walk(full);
      } else if (entry.isFile() && languageOf(entry.name)) {
        seen++;
        if (files.length >= options.maxFiles) truncated = true;
        else files.push(full);
      }
    }
  };
  walk(root);
  return { files, seen, truncated };
}

/**
 * Analyses every TypeScript/JavaScript file under root and returns the report.
 * Never executes project code: it only reads files and runs the compiler front end.
 */
export function analyze(rootDir: string, options: AnalyzeOptions = DEFAULT_OPTIONS): AnalysisReport {
  const started = Date.now();
  const root = resolve(rootDir);
  const listing = listSourceFiles(root, options);
  const toRelative = (absolute: string): string | null => {
    const rel = relative(root, resolve(absolute));
    return rel.startsWith("..") || rel.includes(`..${sep}`) ? null : rel.split(sep).join("/");
  };

  const fileReports: FileReport[] = [];
  const analysable: string[] = [];
  for (const file of listing.files) {
    const path = toRelative(file)!;
    const size = statSync(file).size;
    if (size > options.maxFileBytes) {
      fileReports.push({ path, language: languageOf(file)!, parsed: false, lineCount: 0, diagnostics: [], skippedReason: `larger than ${options.maxFileBytes} bytes` });
    } else {
      analysable.push(file);
    }
  }

  const compilerOptions: ts.CompilerOptions = {
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    allowJs: true,
    checkJs: false,
    jsx: ts.JsxEmit.Preserve,
    noEmit: true,
    strict: true,
    skipLibCheck: true,
    allowImportingTsExtensions: true,
    resolveJsonModule: false,
    types: [],
    noResolve: false,
  };
  const host = ts.createCompilerHost(compilerOptions, true);
  const program = ts.createProgram({ rootNames: analysable, options: compilerOptions, host });
  const checker = program.getTypeChecker();

  const index: IndexedSymbols = { symbols: [], byNode: new Map() };
  const sources: { sf: ts.SourceFile; path: string }[] = [];
  for (const file of analysable) {
    const sf = program.getSourceFile(file);
    const path = toRelative(file)!;
    if (!sf) {
      fileReports.push({ path, language: languageOf(file)!, parsed: false, lineCount: 0, diagnostics: [], skippedReason: "not loaded by the compiler" });
      continue;
    }
    const diagnostics = program.getSyntacticDiagnostics(sf).slice(0, 50).map((d) => {
      const start = d.start ?? 0;
      const s = sf.getLineAndCharacterOfPosition(start);
      const e = sf.getLineAndCharacterOfPosition(start + (d.length ?? 0));
      return {
        range: { startLine: s.line + 1, startColumn: s.character + 1, endLine: e.line + 1, endColumn: e.character + 1 },
        message: ts.flattenDiagnosticMessageText(d.messageText, "\n").slice(0, 500),
        category: d.category === ts.DiagnosticCategory.Error ? ("error" as const) : ("warning" as const),
      };
    });
    fileReports.push({ path, language: languageOf(file)!, parsed: true, lineCount: sf.getLineStarts().length, diagnostics });
    indexSymbols(sf, path, index);
    sources.push({ sf, path });
  }

  const imports: ImportReport[] = [];
  const calls: CallReport[] = [];
  const findings: FindingReport[] = [];
  for (const { sf, path } of sources) {
    imports.push(...collectImports(sf, path, compilerOptions, host, toRelative));
    calls.push(...collectCalls(sf, path, checker, index.byNode));
    const occurrences = new Map<string, number>();
    for (const rule of RULES) {
      rule.check({
        sf,
        path,
        checker,
        report(node, message) {
          const symbolKey = owningSymbol(node, index.byNode) ?? null;
          const code = normalizeCode(node.getText(sf));
          // Same rule, file, owner and code: count occurrences so duplicates stay distinct.
          const base = `${rule.meta.id}|${path}|${symbolKey ?? ""}|${code}`;
          const n = (occurrences.get(base) ?? 0) + 1;
          occurrences.set(base, n);
          findings.push({
            ruleId: rule.meta.id,
            ruleVersion: rule.meta.version,
            severity: rule.meta.severity,
            confidence: rule.meta.confidence,
            message,
            path,
            range: rangeOf(node, sf),
            snippet: snippetOf(node, sf),
            symbolKey,
            fingerprint: sha256(`${base}|${n}`),
          });
        },
      });
    }
  }

  fileReports.sort((a, b) => a.path.localeCompare(b.path));
  return {
    schemaVersion: REPORT_SCHEMA_VERSION,
    adapter: ADAPTER,
    files: fileReports,
    symbols: index.symbols,
    imports,
    calls,
    rules: RULES.map((r) => r.meta),
    findings,
    stats: { filesSeen: listing.seen, filesAnalysed: sources.length, durationMs: Date.now() - started, truncated: listing.truncated },
  };
}

export function readJson(path: string): unknown {
  return JSON.parse(readFileSync(path, "utf8"));
}
