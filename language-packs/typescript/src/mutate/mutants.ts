import ts from "typescript";
import { createHash } from "node:crypto";
import { relative, resolve, sep } from "node:path";
import { COMPILER_OPTIONS, DEFAULT_OPTIONS, listSourceFiles } from "../analyze.ts";
import type { Mutant, MutationOperator } from "./types.ts";

export const TEST_FILE = /(^|\/)(test|tests|__tests__|spec)\/|\.(test|spec)\.[cm]?[jt]sx?$/;

export const OPERATORS: { id: MutationOperator; description: string }[] = [
  { id: "comparison", description: "Moves a comparison boundary: < ↔ <=, > ↔ >=." },
  { id: "equality", description: "Inverts an equality check: === ↔ !==, == ↔ !=." },
  { id: "arithmetic", description: "Swaps arithmetic: + ↔ -, * ↔ /, % → *, and the compound assignments. String concatenation is left alone." },
  { id: "logical", description: "Swaps && ↔ ||." },
  { id: "negation", description: "Removes a logical not: !x → x." },
  { id: "condition", description: "Replaces the condition of an if or ternary with true or false (loops only with false)." },
  { id: "constant", description: "Changes a numeric literal (n → 0, or 1 when n is 0, and n → n + 1) or flips a boolean literal." },
  { id: "call-removal", description: "Removes a statement that only calls a function, awaited or not." },
];

const BINARY: Partial<Record<ts.SyntaxKind, [MutationOperator, string[]]>> = {
  [ts.SyntaxKind.LessThanToken]: ["comparison", ["<="]],
  [ts.SyntaxKind.LessThanEqualsToken]: ["comparison", ["<"]],
  [ts.SyntaxKind.GreaterThanToken]: ["comparison", [">="]],
  [ts.SyntaxKind.GreaterThanEqualsToken]: ["comparison", [">"]],
  [ts.SyntaxKind.EqualsEqualsEqualsToken]: ["equality", ["!=="]],
  [ts.SyntaxKind.ExclamationEqualsEqualsToken]: ["equality", ["==="]],
  [ts.SyntaxKind.EqualsEqualsToken]: ["equality", ["!="]],
  [ts.SyntaxKind.ExclamationEqualsToken]: ["equality", ["=="]],
  [ts.SyntaxKind.PlusToken]: ["arithmetic", ["-"]],
  [ts.SyntaxKind.MinusToken]: ["arithmetic", ["+"]],
  [ts.SyntaxKind.AsteriskToken]: ["arithmetic", ["/"]],
  [ts.SyntaxKind.SlashToken]: ["arithmetic", ["*"]],
  [ts.SyntaxKind.PercentToken]: ["arithmetic", ["*"]],
  [ts.SyntaxKind.PlusEqualsToken]: ["arithmetic", ["-="]],
  [ts.SyntaxKind.MinusEqualsToken]: ["arithmetic", ["+="]],
  [ts.SyntaxKind.AsteriskEqualsToken]: ["arithmetic", ["/="]],
  [ts.SyntaxKind.SlashEqualsToken]: ["arithmetic", ["*="]],
  [ts.SyntaxKind.AmpersandAmpersandToken]: ["logical", ["||"]],
  [ts.SyntaxKind.BarBarToken]: ["logical", ["&&"]],
};

/**
 * Finds mutation sites in non-test source files. Only expressions are mutated,
 * never types, imports or declarations, and every mutant must still parse.
 */
export function findMutants(rootDir: string): Mutant[] {
  const root = resolve(rootDir);
  const toRelative = (file: string) => relative(root, file).split(sep).join("/");
  const files = listSourceFiles(root, DEFAULT_OPTIONS).files.filter((f) => !TEST_FILE.test(toRelative(f)) && !f.endsWith(".d.ts"));
  const program = ts.createProgram({ rootNames: files, options: COMPILER_OPTIONS });
  const checker = program.getTypeChecker();
  const mutants: Mutant[] = [];

  for (const file of files) {
    const sf = program.getSourceFile(file);
    if (!sf) continue;
    const path = toRelative(file);
    const text = sf.getFullText();
    const add = (node: ts.Node, start: number, end: number, operator: MutationOperator, replacement: string) => {
      const original = text.slice(start, end);
      if (original === replacement) return;
      const mutated = text.slice(0, start) + replacement + text.slice(end);
      if (!parses(path, mutated)) return;
      const { line, character } = sf.getLineAndCharacterOfPosition(start);
      mutants.push({
        id: createHash("sha256").update(`${path}|${start}|${end}|${replacement}`).digest("hex").slice(0, 16),
        path, line: line + 1, column: character + 1, operator, original, replacement, start, end,
        symbolKey: enclosingSymbol(node, path),
      });
    };
    const isStringy = (node: ts.Expression) => {
      const type = checker.getTypeAtLocation(node);
      return (type.flags & (ts.TypeFlags.StringLike | ts.TypeFlags.Any | ts.TypeFlags.Unknown)) !== 0
        || (type.isUnion() && type.types.some((t) => (t.flags & ts.TypeFlags.StringLike) !== 0));
    };

    const visit = (node: ts.Node): void => {
      if (ts.isTypeNode(node) || ts.isImportDeclaration(node) || ts.isExportDeclaration(node) || ts.isEnumDeclaration(node)) return;

      if (ts.isBinaryExpression(node)) {
        const entry = BINARY[node.operatorToken.kind];
        const op = node.operatorToken;
        const concatenation = (op.kind === ts.SyntaxKind.PlusToken || op.kind === ts.SyntaxKind.PlusEqualsToken)
          && (isStringy(node.left) || isStringy(node.right));
        if (entry && !concatenation) for (const r of entry[1]) add(node, op.getStart(sf), op.end, entry[0], r);
      } else if (ts.isPrefixUnaryExpression(node) && node.operator === ts.SyntaxKind.ExclamationToken) {
        add(node, node.getStart(sf), node.operand.getStart(sf), "negation", "");
      } else if (ts.isNumericLiteral(node) && !ts.isLiteralTypeNode(node.parent) && !ts.isElementAccessExpression(node.parent)) {
        const value = Number(node.text);
        add(node, node.getStart(sf), node.end, "constant", value === 0 ? "1" : "0");
        if (value !== 0) add(node, node.getStart(sf), node.end, "constant", String(value + 1));
      } else if (node.kind === ts.SyntaxKind.TrueKeyword || node.kind === ts.SyntaxKind.FalseKeyword) {
        if (!ts.isLiteralTypeNode(node.parent)) add(node, node.getStart(sf), node.end, "constant", node.kind === ts.SyntaxKind.TrueKeyword ? "false" : "true");
      }

      if (ts.isIfStatement(node) || ts.isConditionalExpression(node)) {
        const cond = ts.isIfStatement(node) ? node.expression : node.condition;
        if (cond.kind !== ts.SyntaxKind.TrueKeyword && cond.kind !== ts.SyntaxKind.FalseKeyword) {
          add(node, cond.getStart(sf), cond.end, "condition", "true");
          add(node, cond.getStart(sf), cond.end, "condition", "false");
        }
      } else if ((ts.isWhileStatement(node) || ts.isDoStatement(node)) && node.expression.kind !== ts.SyntaxKind.FalseKeyword) {
        add(node, node.expression.getStart(sf), node.expression.end, "condition", "false");
      }

      if (ts.isExpressionStatement(node)) {
        const inner = ts.isAwaitExpression(node.expression) ? node.expression.expression : node.expression;
        if (ts.isCallExpression(inner) && inner.expression.kind !== ts.SyntaxKind.SuperKeyword) {
          add(node, node.getStart(sf), node.end, "call-removal", ";");
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  }
  return mutants;
}

function parses(path: string, text: string): boolean {
  const sf = ts.createSourceFile(path, text, ts.ScriptTarget.ES2022, false, path.endsWith("x") ? ts.ScriptKind.TSX : undefined);
  return (sf as unknown as { parseDiagnostics: unknown[] }).parseDiagnostics.length === 0;
}

function enclosingSymbol(node: ts.Node, path: string): string | null {
  const names: string[] = [];
  for (let n: ts.Node | undefined = node; n; n = n.parent) {
    if ((ts.isFunctionDeclaration(n) || ts.isMethodDeclaration(n) || ts.isClassDeclaration(n)) && n.name) names.unshift(n.name.getText());
    else if ((ts.isArrowFunction(n) || ts.isFunctionExpression(n)) && ts.isVariableDeclaration(n.parent) && ts.isIdentifier(n.parent.name)) names.unshift(n.parent.name.text);
    else if (ts.isConstructorDeclaration(n)) names.unshift("constructor");
  }
  return names.length ? `${path}#${names.join(".")}` : null;
}

/** Applies a mutant to the file's original text. */
export function applyMutant(text: string, mutant: Pick<Mutant, "start" | "end" | "replacement">): string {
  return text.slice(0, mutant.start) + mutant.replacement + text.slice(mutant.end);
}
