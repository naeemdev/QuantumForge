import ts from "typescript";
import type { Rule } from "./rule.ts";
import { enclosingFunction } from "../util.ts";

const COUNT_LIKE = /(count|length|size|total|quantity|num|items)\b/i;

function countLikeName(expression: ts.Expression, sf: ts.SourceFile): string | undefined {
  const e = ts.isParenthesizedExpression(expression) ? expression.expression : expression;
  if (ts.isCallExpression(e) || ts.isPropertyAccessExpression(e)) {
    const name = ts.isCallExpression(e)
      ? ts.isPropertyAccessExpression(e.expression) ? e.expression.name.text : e.expression.getText(sf)
      : e.name.text;
    return COUNT_LIKE.test(name) ? e.getText(sf) : undefined;
  }
  if (ts.isIdentifier(e)) return COUNT_LIKE.test(e.text) ? e.text : undefined;
  return undefined;
}

/** Looks for any zero check of the divisor text anywhere in the enclosing function. */
function guarded(divisor: string, scope: ts.Node, sf: ts.SourceFile): boolean {
  const escaped = divisor.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const text = scope.getText(sf);
  const patterns = [
    new RegExp(`${escaped}\\s*(===|==|!==|!=|<=|>|<)\\s*0\\b`),
    new RegExp(`\\b0\\s*(===|==|!==|!=|<|>=|>)\\s*${escaped}`),
    new RegExp(`!\\s*${escaped}\\b`),
    new RegExp(`${escaped}\\s*\\|\\|`),
    new RegExp(`Math\\.max\\(\\s*${escaped}|Math\\.max\\([^)]*,\\s*${escaped}`),
  ];
  return patterns.some((p) => p.test(text));
}

export const unguardedDivision: Rule = {
  meta: {
    id: "QF-TS-007",
    version: 1,
    name: "unguarded-division",
    summary: "Division by a count that may be zero",
    description:
      "A value is divided by something that looks like a count, length or size, and the enclosing function never checks it against zero. With an empty input the result is Infinity or NaN, which then propagates silently through later arithmetic.",
    remediation: "Handle the empty case explicitly before dividing (return a defined value or raise a clear error).",
    category: "correctness",
    severity: "low",
    confidence: "low",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (ts.isBinaryExpression(node) && (node.operatorToken.kind === ts.SyntaxKind.SlashToken || node.operatorToken.kind === ts.SyntaxKind.SlashEqualsToken)) {
        const divisor = countLikeName(node.right, sf);
        const scope = enclosingFunction(node) ?? sf;
        if (divisor && !guarded(divisor, scope, sf)) {
          report(node, `Divides by '${divisor}' without checking for zero; an empty input yields NaN or Infinity.`);
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
