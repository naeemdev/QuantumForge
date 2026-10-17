import ts from "typescript";
import type { Rule } from "./rule.ts";

export const swallowedException: Rule = {
  meta: {
    id: "QF-TS-003",
    version: 1,
    name: "swallowed-exception",
    summary: "Exception is caught and silently discarded",
    description: "An empty catch block discards the error entirely. The failure leaves no trace in logs or return values.",
    remediation: "Handle the error, log it with context, or let it propagate. If ignoring it is intended, say why in a comment and record it.",
    category: "error-handling",
    severity: "medium",
    confidence: "high",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (ts.isCatchClause(node) && node.block.statements.length === 0) {
        const text = node.block.getText(sf);
        const hasExplanation = /\/\/|\/\*/.test(text);
        report(node, hasExplanation ? "The catch block only contains a comment; the error is discarded." : "Empty catch block; the error is discarded.");
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
