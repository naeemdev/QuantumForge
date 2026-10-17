# Progress

_Last updated: 2026-10-17_

| Milestone | Status |
|---|---|
| M1 Foundation | Complete |
| M2 Code intelligence | Complete for TypeScript/JavaScript |
| M3 Break My Code | In progress: fuzzing, reproductions and campaigns done; mutation and property-based testing open |
| M4–M7 | Not started |

## Milestone 3: Break My Code

Acceptance: *the system finds and reproduces a seeded defect from the bug corpus
without being told where it is.* Met for D6, covered by `BreakMyCodeTest` (real
fuzzer in a worker), `ContainerExecutionTest` (the same in a container, CI) and the
`break-my-code` browser test.

What a campaign on `sample-commerce-api` does:
- **Discovery:** 14 exports; 8 functions fuzzed; 6 skipped with reasons (four classes, `withRetry` is generic, `placeOrder` takes a class instance).
- **Result:** one failure, `averageUnitPrice({ lines: [] })` returns `NaN` (D6). Already minimal, reproduced 3 of 3 times in fresh processes, with a generated node:test file that fails on the shipped code and passes with the fix. No other failures; `addItem` and `salesTax` reject about half the generated inputs through their own validation, which is not reported.
- **Lifecycle:** another seed replays the stored input first and still reproduces it; a static analysis of the fixed code does not touch it; a campaign on the fixed code replays the input, sees it pass and marks the finding `FIXED`; fuzzing the older snapshot again does not reopen it.
- **Limits, stated in every report:** generic oracles only (D1, D2 are out of reach), classes/callbacks/generics skipped (D3, D4, D5), no failures ≠ correct.

## Milestone 2: Code intelligence

Acceptance: *a real TypeScript repository can be analysed and its actual symbols,
dependencies and findings navigated.* Covered end to end by `CodeIntelligenceTest`
and the workflow browser test: index, five suspected findings matching D1, D3–D6,
identity across line shifts, `FIXED`/`REOPENED` lifecycle, triage rules and tenant
isolation. The rules were written knowing the corpus defects, so that result is a
regression check, not evidence of accuracy on other code (backlog N13).

## Test results (local, 2026-10-17)

| Suite | Tests | Result |
|---|---|---|
| Backend (API + worker, JUnit) | 49 | pass; 4 Docker tests and the S3 contract test skip without Docker / `QF_TEST_S3_ENDPOINT` (all run in CI) |
| TypeScript adapter and fuzzer (`node:test`) | 25 | pass |
| Web: typecheck, lint, production build | — | pass |
| Browser end to end (Playwright) | 3 | pass |
| Bug corpus verification script | — | pass |

## Verified only in CI

There is no Docker daemon here, so the container images and the container sandbox
tests (`ContainerSandboxTest`, `ContainerExecutionTest`, now including a fuzz
campaign) run only in CI. `container-gvisor` is not tested anywhere yet (no runsc
on CI runners). OIDC sign-in against a real identity provider has not been exercised.

## Next

1. M3: mutation testing (M3-T4), then user-declared properties (M3-T5)
2. N3 row-level security, N7 encrypted private Git credentials
3. M4 repair verification
