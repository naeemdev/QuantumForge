import ts from "typescript";
import type { Rule } from "./rule.ts";

function isToFixedCall(e: ts.Expression): boolean {
  return ts.isCallExpression(e) && ts.isPropertyAccessExpression(e.expression) && e.expression.name.text === "toFixed";
}

export const floatRounding: Rule = {
  meta: {
    id: "QF-TS-008",
    version: 1,
    name: "binary-float-rounding",
    summary: "Number rounded with toFixed() and converted back",
    description:
      "toFixed() rounds the binary floating-point value, not the decimal one you see. 1.005.toFixed(2) is \"1.00\" because 1.005 is stored as 1.00499999…. Converting the string back to a number bakes the error into later calculations, which matters for money and other decimal quantities.",
    remediation: "Keep money in integer minor units, or round the decimal representation explicitly (e.g. Math.round(Number(`${x}e2`)) / 100), or use a decimal library.",
    category: "numerical",
    severity: "medium",
    confidence: "medium",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && ["Number", "parseFloat"].includes(node.expression.text)) {
        const arg = node.arguments[0];
        if (arg && isToFixedCall(arg)) report(node, `${node.expression.text}(x.toFixed(n)) rounds the binary value; half-way decimals can round the wrong way.`);
      }
      if (ts.isPrefixUnaryExpression(node) && node.operator === ts.SyntaxKind.PlusToken && isToFixedCall(node.operand)) {
        report(node, "+x.toFixed(n) rounds the binary value; half-way decimals can round the wrong way.");
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
