import ts from "typescript";
import type { Rule } from "./rule.ts";

export const insecureTls: Rule = {
  meta: {
    id: "QF-TS-009",
    version: 1,
    name: "tls-verification-disabled",
    summary: "TLS certificate verification is turned off",
    description:
      "Setting rejectUnauthorized: false or NODE_TLS_REJECT_UNAUTHORIZED=0 accepts any certificate, so a network attacker can intercept or modify traffic.",
    remediation: "Keep verification on. For private CAs, pass the CA certificate (ca option or NODE_EXTRA_CA_CERTS).",
    category: "configuration",
    severity: "high",
    confidence: "high",
    languages: ["TypeScript", "JavaScript"],
  },
  check({ sf, report }) {
    const visit = (node: ts.Node): void => {
      if (ts.isPropertyAssignment(node) && node.name.getText(sf).replace(/["']/g, "") === "rejectUnauthorized" && node.initializer.kind === ts.SyntaxKind.FalseKeyword) {
        report(node, "rejectUnauthorized: false disables certificate verification.");
      }
      if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken && /NODE_TLS_REJECT_UNAUTHORIZED/.test(node.left.getText(sf))) {
        const value = node.right.getText(sf).replace(/["'`]/g, "");
        if (value === "0") report(node, "NODE_TLS_REJECT_UNAUTHORIZED=0 disables certificate verification for the whole process.");
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  },
};
