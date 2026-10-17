import ts from "typescript";
import type { SymbolKind, SymbolReport } from "@quantumforge/language-sdk";
import { forEachInScope, rangeOf } from "./util.ts";

/**
 * Cyclomatic complexity (McCabe) of one function body: 1 + decision points.
 * Decision points: if, conditional expression, loop, non-default case, catch,
 * and short-circuit operators (&&, ||, ??). Nested functions are not counted.
 */
export function cyclomaticComplexity(fn: ts.Node): number {
  let complexity = 1;
  forEachInScope(fn, (n) => {
    switch (n.kind) {
      case ts.SyntaxKind.IfStatement:
      case ts.SyntaxKind.ConditionalExpression:
      case ts.SyntaxKind.ForStatement:
      case ts.SyntaxKind.ForInStatement:
      case ts.SyntaxKind.ForOfStatement:
      case ts.SyntaxKind.WhileStatement:
      case ts.SyntaxKind.DoStatement:
      case ts.SyntaxKind.CaseClause:
      case ts.SyntaxKind.CatchClause:
        complexity++;
        break;
      case ts.SyntaxKind.BinaryExpression: {
        const op = (n as ts.BinaryExpression).operatorToken.kind;
        if (op === ts.SyntaxKind.AmpersandAmpersandToken || op === ts.SyntaxKind.BarBarToken || op === ts.SyntaxKind.QuestionQuestionToken) {
          complexity++;
        }
        break;
      }
    }
  });
  return complexity;
}

function isExported(node: ts.Node): boolean {
  const modifiers = ts.canHaveModifiers(node) ? ts.getModifiers(node) : undefined;
  if (modifiers?.some((m) => m.kind === ts.SyntaxKind.ExportKeyword)) return true;
  // `const x = ...` inside an exported variable statement
  if (ts.isVariableDeclaration(node)) {
    const statement = node.parent?.parent;
    return statement != null && ts.isVariableStatement(statement) && isExported(statement);
  }
  return false;
}

function signatureOf(node: ts.SignatureDeclaration, sf: ts.SourceFile): string {
  const params = node.parameters.map((p) => p.getText(sf)).join(", ");
  const type = node.type ? `: ${node.type.getText(sf)}` : "";
  const text = `(${params})${type}`.replace(/\s+/g, " ");
  return text.length > 300 ? text.slice(0, 300) + "…" : text;
}

export interface IndexedSymbols {
  symbols: SymbolReport[];
  /** Declaration node → symbol key, used to resolve call targets. */
  byNode: Map<ts.Node, string>;
}

/** Extracts declarations from one file. Keys are unique within the snapshot. */
export function indexSymbols(sf: ts.SourceFile, path: string, into: IndexedSymbols): void {
  const used = new Map<string, number>();
  const add = (node: ts.Node, kind: SymbolKind, name: string, container: string | null, complexity: number | null, signature: string | null) => {
    const base = `${path}#${container ? `${container}.` : ""}${name}`;
    const n = (used.get(base) ?? 0) + 1;
    used.set(base, n);
    const key = n === 1 ? base : `${base}~${n}`;
    into.symbols.push({ key, kind, name, container, path, range: rangeOf(node, sf), exported: isExported(node), complexity, signature });
    into.byNode.set(node, key);
  };

  const visit = (node: ts.Node, container: string | null): void => {
    if (ts.isFunctionDeclaration(node) && node.name && node.body) {
      add(node, "function", node.name.text, container, cyclomaticComplexity(node), signatureOf(node, sf));
      return; // nested functions are implementation details, not indexed
    }
    if (ts.isClassDeclaration(node) && node.name) {
      const className = node.name.text;
      add(node, "class", className, container, null, null);
      for (const member of node.members) {
        if ((ts.isMethodDeclaration(member) || ts.isGetAccessorDeclaration(member) || ts.isSetAccessorDeclaration(member)) && member.body) {
          const name = member.name.getText(sf);
          add(member, "method", name, className, cyclomaticComplexity(member), signatureOf(member, sf));
        } else if (ts.isConstructorDeclaration(member) && member.body) {
          add(member, "method", "constructor", className, cyclomaticComplexity(member), signatureOf(member, sf));
        }
      }
      return;
    }
    if (ts.isInterfaceDeclaration(node)) {
      add(node, "interface", node.name.text, container, null, null);
      return;
    }
    if (ts.isTypeAliasDeclaration(node)) {
      add(node, "type", node.name.text, container, null, null);
      return;
    }
    if (ts.isEnumDeclaration(node)) {
      add(node, "enum", node.name.text, container, null, null);
      return;
    }
    if (ts.isVariableStatement(node)) {
      for (const decl of node.declarationList.declarations) {
        if (!ts.isIdentifier(decl.name)) continue;
        const init = decl.initializer;
        if (init && (ts.isArrowFunction(init) || ts.isFunctionExpression(init))) {
          add(decl, "function", decl.name.text, container, cyclomaticComplexity(init), signatureOf(init, sf));
          into.byNode.set(init, into.byNode.get(decl)!);
        } else if (isExported(decl)) {
          add(decl, "variable", decl.name.text, container, null, null);
        }
      }
      return;
    }
    if (ts.isModuleDeclaration(node) && node.body && ts.isModuleBlock(node.body)) {
      const name = node.name.getText(sf);
      for (const s of node.body.statements) visit(s, container ? `${container}.${name}` : name);
    }
  };

  for (const statement of sf.statements) visit(statement, null);
}
