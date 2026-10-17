import ts from "typescript";
import { createHash } from "node:crypto";
import type { SourceRange } from "@quantumforge/language-sdk";

export function rangeOf(node: ts.Node, sf: ts.SourceFile): SourceRange {
  const start = sf.getLineAndCharacterOfPosition(node.getStart(sf));
  const end = sf.getLineAndCharacterOfPosition(node.getEnd());
  return { startLine: start.line + 1, startColumn: start.character + 1, endLine: end.line + 1, endColumn: end.character + 1 };
}

export function lineOf(node: ts.Node, sf: ts.SourceFile): number {
  return sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
}

/** First line of a node's text, trimmed and capped, for display next to a finding. */
export function snippetOf(node: ts.Node, sf: ts.SourceFile, max = 240): string {
  const text = node.getText(sf);
  const firstLine = text.split("\n")[0] ?? "";
  return firstLine.length > max ? firstLine.slice(0, max) + "…" : firstLine;
}

/** Whitespace-insensitive form of code, so reformatting does not change fingerprints. */
export function normalizeCode(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

export function sha256(text: string): string {
  return createHash("sha256").update(text).digest("hex");
}

export function isFunctionLike(node: ts.Node): node is ts.FunctionLikeDeclaration {
  return (
    ts.isFunctionDeclaration(node) ||
    ts.isMethodDeclaration(node) ||
    ts.isFunctionExpression(node) ||
    ts.isArrowFunction(node) ||
    ts.isConstructorDeclaration(node) ||
    ts.isGetAccessorDeclaration(node) ||
    ts.isSetAccessorDeclaration(node)
  );
}

/** Nearest enclosing function-like node, or undefined at module level. */
export function enclosingFunction(node: ts.Node): ts.FunctionLikeDeclaration | undefined {
  let current = node.parent;
  while (current) {
    if (isFunctionLike(current)) return current;
    current = current.parent;
  }
  return undefined;
}

/** Visits descendants without entering nested functions or classes. */
export function forEachInScope(node: ts.Node, visit: (n: ts.Node) => void): void {
  const walk = (n: ts.Node): void => {
    visit(n);
    if (n !== node && (isFunctionLike(n) || ts.isClassLike(n))) return;
    ts.forEachChild(n, walk);
  };
  ts.forEachChild(node, walk);
}

export function calleeName(call: ts.CallExpression | ts.NewExpression): string | undefined {
  const e = call.expression;
  if (ts.isIdentifier(e)) return e.text;
  if (ts.isPropertyAccessExpression(e)) return e.name.text;
  return undefined;
}
