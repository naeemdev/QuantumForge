import ts from "typescript";
import type { Rule } from "./rule.ts";
import { forEachInScope, isFunctionLike } from "../util.ts";

const READ = /^(get|read|fetch|load|find|select)/i;
const WRITE = /^(set|write|put|save|update|store|upsert)/i;
const LOCKING = /lock|mutex|serial|transaction|atomic|exclusive|queue/i;

function awaitedMethodCall(expression: ts.Expression | undefined): ts.CallExpression | undefined {
  if (!expression || !ts.isAwaitExpression(expression)) return undefined;
  const inner = expression.expression;
  return ts.isCallExpression(inner) && ts.isPropertyAccessExpression(inner.expression) ? inner : undefined;
}

/** True if fn is a callback passed to something that looks like a lock or transaction. */
function runsUnderLock(fn: ts.Node, sf: ts.SourceFile): boolean {
  let current: ts.Node | undefined = fn;
  while (current) {
    if (isFunctionLike(current) && ts.isCallExpression(current.parent) && LOCKING.test(current.parent.expression.getText(sf))) return true;
    current = current.parent;
  }
  return false;
}

export const awaitReadModifyWrite: Rule = {
  meta: {
    id: "QF-TS-006",
    version: 1,
    name: "await-read-modify-write",
    summary: "Read-modify-write spans an await without mutual exclusion",
    description:
      "A value is read from a store with await, then a value derived from it is written back to the same key with another await. Between the two, other asynchronous operations can run and write the same key, so concurrent callers can lose updates (for example, selling the last item twice).",
    remediation: "Make the update atomic in the store (conditional update, compare-and-set, transaction) or serialise operations on the key.",
    category: "concurrency",
    severity: "medium",
    confidence: "medium",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (isFunctionLike(node) && node.body && node.modifiers?.some((m) => m.kind === ts.SyntaxKind.AsyncKeyword) && !runsUnderLock(node, sf)) {
        const reads = new Map<string, { receiver: string; key: string }>();
        forEachInScope(node.body, (n) => {
          if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name)) {
            const call = awaitedMethodCall(n.initializer);
            if (call && READ.test((call.expression as ts.PropertyAccessExpression).name.text) && call.arguments[0]) {
              reads.set(n.name.text, { receiver: (call.expression as ts.PropertyAccessExpression).expression.getText(sf), key: call.arguments[0].getText(sf) });
            }
          }
          if (ts.isAwaitExpression(n)) {
            const call = awaitedMethodCall(n);
            if (!call) return;
            const access = call.expression as ts.PropertyAccessExpression;
            if (!WRITE.test(access.name.text) || !call.arguments[0]) return;
            const receiver = access.expression.getText(sf);
            const key = call.arguments[0].getText(sf);
            const written = call.arguments.slice(1).map((a) => a.getText(sf)).join(" ");
            for (const [variable, read] of reads) {
              if (read.receiver === receiver && read.key === key && new RegExp(`\\b${variable}\\b`).test(written)) {
                report(call, `'${variable}' is read from ${receiver} and written back after an await; concurrent calls can overwrite each other.`);
              }
            }
          }
        });
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
