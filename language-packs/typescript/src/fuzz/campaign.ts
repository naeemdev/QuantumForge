import { createHash } from "node:crypto";
import { join, resolve } from "node:path";
import { decode, encode, toSource } from "./codec.ts";
import { discoverTargets } from "./discover.ts";
import { generate, prng, shrinkCandidates } from "./generate.ts";
import { ORACLES } from "./oracles.ts";
import { TargetSession } from "./session.ts";
import type { CallOutcome, CampaignReport, CorpusEntry, FailureReport, FuzzTarget, OracleKind, TargetReport } from "./types.ts";

export const FUZZ_ENGINE_VERSION = "0.1.0";

export interface CampaignOptions {
  seed: number;
  iterationsPerTarget: number;
  callTimeoutMs: number;
  /** Wall-clock budget per target, including shrinking and reproduction. */
  targetBudgetMs: number;
  maxFailuresPerTarget: number;
  shrinkEvaluations: number;
  reproductionAttempts: number;
  /** Wall-clock budget for the whole campaign; targets not started in time are skipped. */
  campaignBudgetMs: number;
  corpus: CorpusEntry[];
}

export const DEFAULT_CAMPAIGN: CampaignOptions = {
  seed: 1,
  iterationsPerTarget: 300,
  callTimeoutMs: 1000,
  targetBudgetMs: 30_000,
  maxFailuresPerTarget: 3,
  shrinkEvaluations: 200,
  reproductionAttempts: 3,
  campaignBudgetMs: 10 * 60_000,
  corpus: [],
};

export const LIMITATIONS = [
  "Only exported functions whose parameters are numbers, strings, booleans, literals, null/undefined, arrays, tuples, unions or plain object shapes are fuzzed. Classes, callbacks, generics and built-in types are skipped and listed with the reason.",
  "Inputs are generated from declared TypeScript types only. A function whose real precondition is narrower than its type (for example a quantity that must be positive) can be reported for inputs its callers never pass.",
  "The oracles are generic: engine errors, NaN/Infinity results, hangs and crashes. Wrong-but-plausible results, such as incorrect rounding, are not detected without a specification.",
  "Module state persists across calls within a target, so a failure may depend on earlier calls. Each failure is re-run in fresh processes and the reproduction count is reported.",
  "Absence of failures is not evidence of correctness: it means these oracles found nothing in the inputs that were tried.",
];

const failureSignature = (o: CallOutcome) =>
  o.kind === "non-finite-result"
    ? `${o.kind}|${o.message?.startsWith("returned ") ? "result" : o.message}`
    : `${o.kind}|${o.errorName ?? ""}|${(o.message ?? "").replace(/\d+/g, "N")}`;

const isFailure = (o: CallOutcome): o is CallOutcome & { kind: OracleKind } =>
  o.kind !== "ok" && o.kind !== "rejected";

function seedFor(seed: number, key: string): number {
  return createHash("sha256").update(`${seed}|${key}`).digest().readUInt32BE(0);
}

function generateArgs(target: FuzzTarget, random: () => number): unknown[] {
  const args = target.parameters.map((p) => (p.optional && random() < 0.2 ? undefined : generate(p.type, random)));
  while (args.length && args[args.length - 1] === undefined && target.parameters[args.length - 1]?.optional) args.pop();
  return args;
}

export async function runCampaign(rootDir: string, options: CampaignOptions = DEFAULT_CAMPAIGN): Promise<CampaignReport> {
  const started = Date.now();
  const root = resolve(rootDir);
  const { targets, skipped } = discoverTargets(root);
  const reports: TargetReport[] = [];
  let totalCalls = 0;

  for (const target of targets) {
    if (Date.now() - started > options.campaignBudgetMs) {
      skipped.push({ path: target.path, exportName: target.exportName, line: target.line, reason: "campaign time budget exhausted before this function was reached" });
      continue;
    }
    const modulePath = join(root, target.path);
    const session = new TargetSession(modulePath, target.exportName, target.kind);
    const loadError = await session.load();
    if (loadError) {
      skipped.push({ path: target.path, exportName: target.exportName, line: target.line, reason: loadError });
      session.reset();
      continue;
    }
    const targetStarted = Date.now();
    const random = prng(seedFor(options.seed, `${target.path}#${target.exportName}`));
    const failures: FailureReport[] = [];
    const seen = new Set<string>();
    let iterations = 0;
    let rejected = 0;
    // Known failing inputs first: this is how a fix is confirmed or a regression caught.
    const replay = options.corpus
      .filter((e) => e.path === target.path && e.exportName === target.exportName && Array.isArray(e.input))
      .map((e) => decode(e.input) as unknown[]);
    const replayed = replay.length;
    let exploring = true;
    const call = async (s: TargetSession, args: unknown[]) => {
      totalCalls++;
      return s.call(encode(args) as unknown[], options.callTimeoutMs);
    };

    while (replay.length || (exploring && iterations < options.iterationsPerTarget + replayed && Date.now() - targetStarted < options.targetBudgetMs)) {
      // No parameters means every call is the same call; once is enough.
      if (!replay.length && target.parameters.length === 0 && iterations > 0) break;
      iterations++;
      const args = replay.shift() ?? generateArgs(target, random);
      const outcome = await call(session, args);
      if (outcome.kind === "rejected") rejected++;
      if (!isFailure(outcome)) continue;
      const signature = failureSignature(outcome);
      if (seen.has(signature)) continue;
      seen.add(signature);

      // Shrink: keep any simpler input that fails with the same signature.
      let current = args;
      let currentOutcome = outcome;
      let steps = 0;
      let evaluations = 0;
      const shrinkable = outcome.kind !== "timeout" && outcome.kind !== "crash";
      let improved = shrinkable;
      while (improved && evaluations < options.shrinkEvaluations) {
        improved = false;
        for (let i = 0; i < target.parameters.length && !improved; i++) {
          const param = target.parameters[i]!;
          const candidates = [
            ...(param.optional && current[i] !== undefined ? [undefined] : []),
            ...shrinkCandidates(param.type, current[i]),
          ];
          for (const candidate of candidates) {
            if (evaluations++ >= options.shrinkEvaluations) break;
            const next = current.map((v, j) => (j === i ? candidate : v));
            const o = await call(session, next);
            if (isFailure(o) && failureSignature(o) === signature) {
              current = next;
              currentOutcome = o;
              steps++;
              improved = true;
              break;
            }
          }
        }
      }

      // Reproduce in fresh processes, so module state from the campaign cannot be the cause.
      let reproduced = 0;
      for (let attempt = 0; attempt < options.reproductionAttempts; attempt++) {
        const fresh = new TargetSession(modulePath, target.exportName, target.kind);
        const o = await call(fresh, current);
        fresh.reset();
        if (isFailure(o) && failureSignature(o) === signature) reproduced++;
      }

      failures.push({
        oracle: currentOutcome.kind as OracleKind,
        message: currentOutcome.message ?? "",
        errorName: currentOutcome.errorName ?? null,
        stack: currentOutcome.stack ?? null,
        input: encode(current) as unknown[],
        originalInput: encode(args) as unknown[],
        shrinkSteps: steps,
        reproductions: { attempts: options.reproductionAttempts, reproduced },
        reproductionTest: reproductionTest(target, current, currentOutcome.kind as OracleKind, options.callTimeoutMs),
        fingerprint: createHash("sha256").update(`fuzz|${target.path}|${target.exportName}|${signature}`).digest("hex"),
      });
      // Stop exploring, but still replay the rest of the corpus.
      if (failures.length >= options.maxFailuresPerTarget || outcome.kind === "timeout" || outcome.kind === "crash") exploring = false;
    }
    session.reset();
    reports.push({
      kind: target.kind,
      description: target.description,
      path: target.path,
      exportName: target.exportName,
      signature: target.signature,
      line: target.line,
      iterations,
      replayed,
      rejectedInputs: rejected,
      durationMs: Date.now() - targetStarted,
      failures,
    });
  }

  return {
    schemaVersion: 1,
    engine: { id: "typescript-fuzz", version: FUZZ_ENGINE_VERSION, oracles: ORACLES, limitations: LIMITATIONS },
    config: { seed: options.seed, iterationsPerTarget: options.iterationsPerTarget, callTimeoutMs: options.callTimeoutMs, corpusEntries: options.corpus.length },
    targets: reports,
    skipped: skipped.sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line),
    stats: {
      targetsFound: reports.length + skipped.length,
      targetsTested: reports.length,
      totalCalls,
      failures: reports.reduce((n, r) => n + r.failures.length, 0),
      durationMs: Date.now() - started,
    },
  };
}

/** A self-contained node:test file that fails while the defect is present. Save it at the project root. */
export function reproductionTest(target: FuzzTarget, args: unknown[], oracle: OracleKind, timeoutMs: number): string {
  const values = (decode(encode(args)) as unknown[]).map(toSource);
  const call = `${target.exportName}(${values.join(", ")})`;
  const header = [
    `import { test } from "node:test";`,
    `import assert from "node:assert/strict";`,
    `import { ${target.exportName} } from "./${target.path}";`,
    ``,
  ];
  const name = JSON.stringify(`${target.exportName}: ${oracle} for ${call.length > 80 ? `${call.slice(0, 77)}...` : call}`);
  switch (oracle) {
    case "property-violated":
      return [
        ...header,
        `test(${JSON.stringify(target.description ?? `${target.exportName} holds`)}, async () => {`,
        `  // Returns false or throws while the property is violated.`,
        `  assert.notEqual(await ${call}, false);`,
        `});`,
        ``,
      ].join("\n");
    case "non-finite-result":
      return [
        ...header,
        `const finite = (v: unknown, depth = 0): boolean =>`,
        `  typeof v === "number" ? Number.isFinite(v)`,
        `    : depth < 2 && v !== null && typeof v === "object" ? Object.values(v).every((x) => finite(x, depth + 1))`,
        `    : true;`,
        ``,
        `test(${name}, async () => {`,
        `  const result = await ${call};`,
        `  assert.ok(finite(result), \`expected a finite result, got \${typeof result === "number" ? result : JSON.stringify(result)}\`);`,
        `});`,
        ``,
      ].join("\n");
    case "timeout":
      return [
        ...header,
        `test(${name}, { timeout: ${timeoutMs * 2} }, async () => {`,
        `  await ${call};`,
        `});`,
        ``,
      ].join("\n");
    default:
      return [
        ...header,
        `test(${name}, async () => {`,
        `  await assert.doesNotReject(async () => { await ${call}; });`,
        `});`,
        ``,
      ].join("\n");
  }
}
