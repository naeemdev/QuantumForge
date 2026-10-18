import ts from "typescript";
import { relative, resolve, sep } from "node:path";
import { COMPILER_OPTIONS, DEFAULT_OPTIONS, listSourceFiles } from "../analyze.ts";
import type { FuzzTarget, SkippedTarget, TypeSpec } from "./types.ts";

const TEST_FILE = /(^|\/)(test|tests|__tests__|spec)\/|\.(test|spec)\.[cm]?[jt]sx?$/;
/** Files whose exported functions are properties rather than code under test. */
export const PROPERTY_FILE = /\.propert(y|ies)\.[cm]?[jt]sx?$/;
const MAX_TYPE_DEPTH = 4;

class Unsupported extends Error {}

/**
 * Finds exported functions whose parameters can be generated from their
 * declared types, and the properties declared in *.properties.ts files. Anything that needs values we cannot construct faithfully
 * (functions, class instances, generics, any/unknown) is skipped with a reason
 * instead of being fuzzed with guesses.
 */
export function discoverTargets(rootDir: string): { targets: FuzzTarget[]; skipped: SkippedTarget[] } {
  const root = resolve(rootDir);
  const toRelative = (file: string) => relative(root, file).split(sep).join("/");
  const files = listSourceFiles(root, DEFAULT_OPTIONS).files.filter((f) => !TEST_FILE.test(toRelative(f)) && !f.endsWith(".d.ts"));
  const program = ts.createProgram({ rootNames: files, options: COMPILER_OPTIONS });
  const checker = program.getTypeChecker();
  const targets: FuzzTarget[] = [];
  const skipped: SkippedTarget[] = [];

  for (const file of files) {
    const sf = program.getSourceFile(file);
    const moduleSymbol = sf && checker.getSymbolAtLocation(sf);
    if (!sf || !moduleSymbol) continue;
    const path = toRelative(file);
    for (const exported of checker.getExportsOfModule(moduleSymbol)) {
      const symbol = exported.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(exported) : exported;
      const declaration = symbol.valueDeclaration;
      if (!declaration || declaration.getSourceFile() !== sf) continue;
      const exportName = exported.getName();
      const line = sf.getLineAndCharacterOfPosition(declaration.getStart(sf)).line + 1;
      if (symbol.flags & ts.SymbolFlags.Class) {
        skipped.push({ path, exportName, line, reason: "class: constructing instances and choosing method sequences is not supported yet" });
        continue;
      }
      if (!(symbol.flags & (ts.SymbolFlags.Function | ts.SymbolFlags.Variable))) continue;
      const type = checker.getTypeOfSymbolAtLocation(symbol, declaration);
      const signatures = type.getCallSignatures();
      if (signatures.length === 0) continue;
      if (signatures.length > 1) {
        skipped.push({ path, exportName, line, reason: "overloaded function" });
        continue;
      }
      const signature = signatures[0]!;
      const text = `${exportName}${checker.signatureToString(signature)}`;
      if (signature.typeParameters?.length) {
        skipped.push({ path, exportName, line, reason: "generic function" });
        continue;
      }
      try {
        const parameters = signature.getParameters().map((p) => {
          const decl = p.valueDeclaration as ts.ParameterDeclaration | undefined;
          if (decl?.dotDotDotToken) throw new Unsupported(`rest parameter '${p.getName()}'`);
          const optional = !!decl && (!!decl.questionToken || !!decl.initializer);
          let paramType = checker.getTypeOfSymbolAtLocation(p, declaration);
          if (optional) paramType = checker.getNonNullableType(paramType);
          return { name: p.getName(), optional, type: toSpec(paramType, checker, 0, `parameter '${p.getName()}'`) };
        });
        const returnType = checker.getReturnTypeOfSignature(signature);
        const isAsync = returnType.getSymbol()?.getName() === "Promise";
        const property = PROPERTY_FILE.test(path);
        const doc = ts.displayPartsToString(symbol.getDocumentationComment(checker)).split("\n")[0]?.trim() || null;
        targets.push({ kind: property ? "property" : "function", description: doc, path, exportName, signature: text, line, parameters, async: isAsync });
      } catch (error) {
        if (!(error instanceof Unsupported)) throw error;
        skipped.push({ path, exportName, line, reason: `unsupported ${error.message}` });
      }
    }
  }
  targets.sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
  skipped.sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
  return { targets, skipped };
}

function toSpec(type: ts.Type, checker: ts.TypeChecker, depth: number, where: string): TypeSpec {
  if (depth > MAX_TYPE_DEPTH) throw new Unsupported(`${where}: type nested too deeply`);
  const f = type.flags;
  if (f & (ts.TypeFlags.Any | ts.TypeFlags.Unknown)) throw new Unsupported(`${where}: type '${checker.typeToString(type)}' has no shape to generate from`);
  if (f & ts.TypeFlags.NumberLiteral) return { kind: "literal", value: (type as ts.NumberLiteralType).value };
  if (f & ts.TypeFlags.StringLiteral) return { kind: "literal", value: (type as ts.StringLiteralType).value };
  if (f & ts.TypeFlags.BooleanLiteral) return { kind: "literal", value: checker.typeToString(type) === "true" };
  if (f & ts.TypeFlags.Number) return { kind: "number" };
  if (f & ts.TypeFlags.String) return { kind: "string" };
  if (f & ts.TypeFlags.Boolean) return { kind: "boolean" };
  if (f & ts.TypeFlags.Null) return { kind: "null" };
  if (f & (ts.TypeFlags.Undefined | ts.TypeFlags.Void)) return { kind: "undefined" };
  if (type.isUnion()) {
    const options = type.types.map((t) => toSpec(t, checker, depth + 1, where));
    // `boolean` arrives as `true | false`; keep it as one option so it is not over-weighted.
    const bools = options.filter((o) => o.kind === "literal" && typeof o.value === "boolean");
    const rest = options.filter((o) => !(o.kind === "literal" && typeof o.value === "boolean"));
    return { kind: "union", options: bools.length === 2 ? [...rest, { kind: "boolean" }] : options };
  }
  if (checker.isTupleType(type)) {
    return { kind: "tuple", elements: checker.getTypeArguments(type as ts.TypeReference).map((t) => toSpec(t, checker, depth + 1, where)) };
  }
  if (checker.isArrayType(type)) {
    const [element] = checker.getTypeArguments(type as ts.TypeReference);
    return { kind: "array", element: toSpec(element!, checker, depth + 1, where) };
  }
  if (f & ts.TypeFlags.Object) {
    const name = checker.typeToString(type);
    if (type.getCallSignatures().length || type.getConstructSignatures().length) throw new Unsupported(`${where}: function type '${name}'`);
    const symbol = type.getSymbol();
    if (symbol && symbol.flags & ts.SymbolFlags.Class) throw new Unsupported(`${where}: class instance '${name}'`);
    if (symbol && isLibraryType(symbol)) throw new Unsupported(`${where}: built-in type '${name}'`);
    if (checker.getIndexInfosOfType(type).length) throw new Unsupported(`${where}: index signature in '${name}'`);
    const properties = checker.getPropertiesOfType(type).map((p) => {
      const decl = p.valueDeclaration ?? p.declarations?.[0];
      if (!decl) throw new Unsupported(`${where}: property '${p.getName()}'`);
      if (ts.isMethodSignature(decl) || ts.isMethodDeclaration(decl)) throw new Unsupported(`${where}: method '${p.getName()}' in '${name}'`);
      const optional = !!(p.flags & ts.SymbolFlags.Optional);
      let propType = checker.getTypeOfSymbolAtLocation(p, decl);
      if (optional) propType = checker.getNonNullableType(propType);
      return { name: p.getName(), optional, type: toSpec(propType, checker, depth + 1, `${where}.${p.getName()}`) };
    });
    return { kind: "object", properties };
  }
  throw new Unsupported(`${where}: type '${checker.typeToString(type)}'`);
}

function isLibraryType(symbol: ts.Symbol): boolean {
  return (symbol.declarations ?? []).some((d) => {
    const file = d.getSourceFile().fileName;
    return /\/typescript\/lib\/lib\.[^/]*\.d\.ts$/.test(file) || file.includes("/node_modules/@types/");
  });
}
