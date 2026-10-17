import type ts from "typescript";
import type { RuleReport } from "@quantumforge/language-sdk";

export interface RuleContext {
  sf: ts.SourceFile;
  path: string;
  checker: ts.TypeChecker;
  /** Reports a finding at node. The engine adds location, snippet, owner symbol and fingerprint. */
  report(node: ts.Node, message: string): void;
}

/**
 * A static analysis rule. `meta.version` must be bumped whenever detection logic
 * changes, so stored findings record exactly which logic produced them.
 */
export interface Rule {
  meta: RuleReport;
  check(context: RuleContext): void;
}
