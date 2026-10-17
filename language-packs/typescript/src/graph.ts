import ts from "typescript";
import type { CallReport, ImportReport } from "@quantumforge/language-sdk";
import { isFunctionLike, lineOf } from "./util.ts";

function packageNameOf(specifier: string): string {
  if (specifier.startsWith("node:")) return specifier;
  const parts = specifier.split("/");
  return specifier.startsWith("@") ? parts.slice(0, 2).join("/") : (parts[0] ?? specifier);
}

/**
 * Module dependencies of one file: static imports and re-exports, dynamic
 * import() and require() with literal specifiers. Relative specifiers are
 * resolved with the compiler's module resolution; anything that resolves
 * outside the snapshot root is treated as external.
 */
export function collectImports(
  sf: ts.SourceFile,
  path: string,
  options: ts.CompilerOptions,
  host: ts.ModuleResolutionHost,
  toRelative: (absolute: string) => string | null,
): ImportReport[] {
  const out: ImportReport[] = [];
  const record = (specifier: string, node: ts.Node) => {
    const relative = specifier.startsWith(".") || specifier.startsWith("/");
    const resolved = ts.resolveModuleName(specifier, sf.fileName, options, host).resolvedModule;
    const inside = resolved && !resolved.isExternalLibraryImport ? toRelative(resolved.resolvedFileName) : null;
    out.push({
      from: path,
      specifier,
      to: inside,
      external: !relative && inside == null,
      packageName: !relative && inside == null ? packageNameOf(specifier) : null,
      line: lineOf(node, sf),
    });
  };
  const visit = (node: ts.Node): void => {
    if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      record(node.moduleSpecifier.text, node);
    } else if (ts.isCallExpression(node) && node.arguments.length === 1 && ts.isStringLiteralLike(node.arguments[0]!)) {
      const isDynamicImport = node.expression.kind === ts.SyntaxKind.ImportKeyword;
      const isRequire = ts.isIdentifier(node.expression) && node.expression.text === "require";
      if (isDynamicImport || isRequire) record((node.arguments[0] as ts.StringLiteralLike).text, node);
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return out;
}

/** The indexed symbol that owns a node: the closest enclosing declaration we gave a key. */
export function owningSymbol(node: ts.Node, byNode: Map<ts.Node, string>): string | undefined {
  let current: ts.Node | undefined = node.parent;
  while (current) {
    const key = byNode.get(current);
    if (key && (isFunctionLike(current) || ts.isVariableDeclaration(current))) return key;
    current = current.parent;
  }
  return undefined;
}

/**
 * Direct call edges between indexed symbols, resolved through the type checker
 * (including imports and aliases). Calls through dynamic dispatch on values whose
 * declaration cannot be determined statically are not recorded.
 */
export function collectCalls(sf: ts.SourceFile, path: string, checker: ts.TypeChecker, byNode: Map<ts.Node, string>): CallReport[] {
  const seen = new Set<string>();
  const out: CallReport[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
      const from = owningSymbol(node, byNode);
      const target = ts.isPropertyAccessExpression(node.expression) ? node.expression.name : node.expression;
      let symbol = checker.getSymbolAtLocation(target);
      if (symbol && symbol.flags & ts.SymbolFlags.Alias) symbol = checker.getAliasedSymbol(symbol);
      const declaration = symbol?.valueDeclaration ?? symbol?.declarations?.[0];
      let to = declaration ? byNode.get(declaration) : undefined;
      if (!to && declaration && ts.isVariableDeclaration(declaration) && declaration.initializer) to = byNode.get(declaration.initializer);
      if (from && to && from !== to) {
        const id = `${from}->${to}`;
        if (!seen.has(id)) {
          seen.add(id);
          out.push({ from, to, path, line: (node.getSourceFile() === sf ? sf : node.getSourceFile()).getLineAndCharacterOfPosition(node.getStart()).line + 1 });
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return out;
}
