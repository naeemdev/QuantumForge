import ts from "typescript";
import type { Rule } from "./rule.ts";

const TIMER_FUNCTIONS = new Set(["setTimeout", "setInterval"]);

export const unsafeDynamicEvaluation: Rule = {
  meta: {
    id: "QF-TS-001",
    version: 1,
    name: "unsafe-dynamic-evaluation",
    summary: "Code is built from strings at runtime",
    description:
      "eval(), the Function constructor and string arguments to setTimeout/setInterval compile strings into code. If any part of the string can be influenced by input, this is code injection.",
    remediation: "Replace with direct function calls or a data-driven lookup. Never pass strings to timers.",
    category: "security",
    severity: "high",
    confidence: "high",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
        const name = node.expression.text;
        if (name === "eval") report(node, "eval() executes a string as code.");
        if (name === "Function") report(node, "Function() compiles a string into a function.");
        const first = node.arguments[0];
        if (TIMER_FUNCTIONS.has(name) && first && (ts.isStringLiteralLike(first) || ts.isTemplateExpression(first))) {
          report(node, `${name}() with a string argument evaluates it as code.`);
        }
      }
      if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "Function") {
        report(node, "new Function() compiles a string into a function.");
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
