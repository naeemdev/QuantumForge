import ts from "typescript";
import type { Rule } from "./rule.ts";
import { forEachInScope } from "../util.ts";

function isLoop(node: ts.Node): node is ts.IterationStatement {
  return ts.isForStatement(node) || ts.isWhileStatement(node) || ts.isDoStatement(node) || ts.isForOfStatement(node) || ts.isForInStatement(node);
}

/**
 * Does the catch block look at the error other than to rethrow it? Reading a
 * property, `instanceof`, comparing it or passing it to a function all count as
 * classifying the error.
 */
function inspectsError(clause: ts.CatchClause): boolean {
  const binding = clause.variableDeclaration?.name;
  if (!binding || !ts.isIdentifier(binding)) return false;
  const name = binding.text;
  let inspected = false;
  forEachInScope(clause.block, (n) => {
    if (inspected || !ts.isIdentifier(n) || n.text !== name || n === binding) return;
    const parent = n.parent;
    if (ts.isThrowStatement(parent)) return;
    inspected = true;
  });
  return inspected;
}

function alwaysExits(block: ts.Block): boolean {
  const last = block.statements[block.statements.length - 1];
  return last != null && (ts.isThrowStatement(last) || ts.isReturnStatement(last) || ts.isBreakStatement(last));
}

export const indiscriminateRetry: Rule = {
  meta: {
    id: "QF-TS-004",
    version: 1,
    name: "indiscriminate-retry",
    summary: "Retry loop retries every error without classifying it",
    description:
      "A loop retries an operation after catching an error without looking at what the error was. Permanent failures (validation errors, 4xx responses, programming errors) are retried as if transient, which delays the real error and multiplies load on the dependency.",
    remediation: "Inspect the error and retry only transient failures (timeouts, 5xx, 429), with a bounded number of attempts and backoff.",
    category: "reliability",
    severity: "medium",
    confidence: "medium",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (isLoop(node)) {
        forEachInScope(node.statement, (n) => {
          if (!ts.isTryStatement(n) || !n.catchClause) return;
          const tryText = n.tryBlock.getText(sf);
          const callsSomething = /\bawait\b|\breturn\b/.test(tryText);
          if (callsSomething && !alwaysExits(n.catchClause.block) && !inspectsError(n.catchClause)) {
            report(n.catchClause, "This retry loop catches every error and retries without checking whether the failure is transient.");
          }
        });
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
