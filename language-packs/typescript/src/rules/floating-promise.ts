import ts from "typescript";
import type { Rule } from "./rule.ts";

/** True when the type has a callable `then` member (Promise or thenable). */
export function isThenable(type: ts.Type, checker: ts.TypeChecker): boolean {
  if (type.isUnion()) return type.types.some((t) => isThenable(t, checker));
  const then = type.getProperty("then");
  if (!then) return false;
  const thenType = checker.getTypeOfSymbol(then);
  return thenType.getCallSignatures().length > 0;
}

function handledChain(expression: ts.Expression): boolean {
  // x.catch(...) or x.then(onFulfilled, onRejected) explicitly handles rejection.
  if (ts.isCallExpression(expression) && ts.isPropertyAccessExpression(expression.expression)) {
    const method = expression.expression.name.text;
    if (method === "catch") return true;
    if (method === "then" && expression.arguments.length >= 2) return true;
  }
  return false;
}

export const floatingPromise: Rule = {
  meta: {
    id: "QF-TS-002",
    version: 1,
    name: "floating-promise",
    summary: "Promise is neither awaited nor handled",
    description:
      "A call that returns a promise is used as a statement without await, return, void or a rejection handler. The caller continues as if the operation succeeded, and a rejection surfaces later as an unhandled rejection unrelated to the caller.",
    remediation: "await the promise, return it, attach .catch(), or mark intentional fire-and-forget with `void`.",
    category: "error-handling",
    severity: "medium",
    confidence: "high",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, checker, report }) {
    const visit = (node: ts.Node): void => {
      if (ts.isExpressionStatement(node)) {
        const expression = node.expression;
        if ((ts.isCallExpression(expression) || ts.isNewExpression(expression)) && !handledChain(expression)) {
          const type = checker.getTypeAtLocation(expression);
          if (!(type.flags & ts.TypeFlags.Any) && isThenable(type, checker)) {
            report(expression, "The returned promise is not awaited or handled; failures will be lost.");
          }
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
