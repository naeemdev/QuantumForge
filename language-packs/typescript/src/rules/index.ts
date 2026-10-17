import type { Rule } from "./rule.ts";
import { unsafeDynamicEvaluation } from "./unsafe-eval.ts";
import { floatingPromise } from "./floating-promise.ts";
import { swallowedException } from "./swallowed-exception.ts";
import { indiscriminateRetry } from "./indiscriminate-retry.ts";
import { sqlStringConstruction } from "./sql-injection.ts";
import { awaitReadModifyWrite } from "./await-read-modify-write.ts";
import { unguardedDivision } from "./unguarded-division.ts";
import { floatRounding } from "./float-rounding.ts";
import { insecureTls } from "./insecure-tls.ts";

export const RULES: readonly Rule[] = [
  unsafeDynamicEvaluation,
  floatingPromise,
  swallowedException,
  indiscriminateRetry,
  sqlStringConstruction,
  awaitReadModifyWrite,
  unguardedDivision,
  floatRounding,
  insecureTls,
];
