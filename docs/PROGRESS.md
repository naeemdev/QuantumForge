# Progress

_Last updated: 2026-10-17_

| Milestone | Status |
|---|---|
| M1 Foundation | Complete (container sandboxes and images verified only in CI, see below) |
| M2 Code intelligence | Complete for TypeScript/JavaScript |
| M3–M7 | Not started |

## Milestone 2: Code intelligence

Acceptance: *a real TypeScript repository can be analysed and its actual symbols,
dependencies and findings navigated.* This is covered end to end by
`CodeIntelligenceTest` and was also exercised in the browser.

The test runs the real adapter inside a worker against `sample-commerce-api`:
- **Index:** 10 files, 30 symbols with kinds, containers, signatures and complexity; resolved import edges (e.g. `src/orders.ts → src/cart.ts`); external modules (`node:test`, …); call edges resolved through the type checker (e.g. `averageUnitPrice → itemCount`).
- **Findings:** five suspected issues, matching the five statically detectable seeded defects (D1, D3, D4, D5, D6) and nothing else. D2 (missing validation) is not detectable by these rules, and that is documented.
- **Identity:** shifting `money.ts` by three lines keeps the same finding with a second occurrence at the new line.
- **Lifecycle:** applying the fixes turns open findings `FIXED` while a `FALSE_POSITIVE` decision stays. Re-analysing the older snapshot does not reopen them. A new snapshot that reintroduces the bug turns the finding `REOPENED`, with full history.
- **Triage:** dismissals need a reason, stale versions are rejected, `FIXED` cannot be set by hand, and viewers cannot triage.
- **Isolation:** another tenant gets 404 on analyses, findings, symbols, graphs and annotations.

**Caveat on accuracy:** the rules were written knowing the corpus defects, so the
perfect corpus result is a regression check, not evidence of precision or recall on
other code (backlog N13).

## Test results (local, 2026-10-17)

| Suite | Tests | Result |
|---|---|---|
| Backend (API + worker, JUnit) | 41 | 40 pass, 1 skipped (S3 contract test; runs in CI against MinIO) |
| TypeScript adapter (`node:test`) | 15 | pass |
| Web: typecheck, lint, production build | — | pass |
| Bug corpus verification script | — | pass |

## Not verified in this environment

There is no Docker daemon and MinIO cannot be downloaded here, so the following run
only in CI:
- the container images, including the new analyzer stage in the worker image
- the S3 storage path
- the `container` / `container-gvisor` sandboxes, which are implemented but have not been executed

OIDC sign-in against a real identity provider has not been exercised; tests use the
development issuer.

## Next

1. N1 container-mode CI test, N2 Playwright in CI
2. N3 row-level security, N7 encrypted private Git credentials
3. M3 Break My Code: property-based generation and mutation testing against the corpus
