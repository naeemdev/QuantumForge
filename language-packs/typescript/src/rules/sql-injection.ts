import ts from "typescript";
import type { Rule } from "./rule.ts";
import { calleeName } from "../util.ts";

const QUERY_METHODS = new Set(["query", "execute", "exec", "raw", "unsafe", "$queryRawUnsafe", "$executeRawUnsafe", "prepare"]);
const SQL = /\b(select\s.+\sfrom|insert\s+into|update\s+\S+\s+set|delete\s+from)\b/i;

function dynamicSqlText(arg: ts.Expression, sf: ts.SourceFile): boolean {
  if (ts.isTemplateExpression(arg)) return SQL.test(arg.getText(sf));
  if (ts.isBinaryExpression(arg) && arg.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const text = arg.getText(sf);
    const hasNonLiteral = (e: ts.Expression): boolean =>
      ts.isBinaryExpression(e) ? hasNonLiteral(e.left) || hasNonLiteral(e.right) : !ts.isStringLiteralLike(e) && !ts.isNumericLiteral(e);
    return SQL.test(text) && hasNonLiteral(arg);
  }
  return false;
}

export const sqlStringConstruction: Rule = {
  meta: {
    id: "QF-TS-005",
    version: 1,
    name: "sql-string-construction",
    summary: "SQL statement built by string interpolation",
    description:
      "A SQL statement is assembled with template interpolation or string concatenation and passed to a query function. If an interpolated value comes from input, this is SQL injection. Tagged templates such as sql`...` are not flagged because they parameterise values.",
    remediation: "Use bound parameters (placeholders) or a query builder that parameterises values.",
    category: "security",
    severity: "high",
    confidence: "medium",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (ts.isCallExpression(node)) {
        const name = calleeName(node);
        const first = node.arguments[0];
        if (name && QUERY_METHODS.has(name) && first && dynamicSqlText(first, sf)) {
          report(node, `SQL text passed to ${name}() is built from interpolated values.`);
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
