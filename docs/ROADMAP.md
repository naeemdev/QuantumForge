# Roadmap

Milestones are delivered in order. Each is done only when its acceptance criteria
pass with automated tests and the docs match the behaviour. Current status is in
`PROGRESS.md`.

| Milestone | Scope | Acceptance |
|---|---|---|
| **M1 Foundation** ✅ | Monorepo, API, web, PostgreSQL/Flyway, OIDC foundation, organizations, memberships, RBAC, projects, repositories, secure snapshot ingestion, job orchestration, evidence storage, Docker Compose, integration tests | A user authenticates, creates an organization, registers a project, imports a snapshot, submits a controlled test job, monitors it and inspects real evidence; another tenant cannot see it |
| **M2 Code intelligence** ✅ | TypeScript language adapter (TypeScript compiler API), source indexing, symbols, import/dependency graph, rule framework with versioned rules, findings and occurrences with history, findings explorer, Monaco viewer with findings by line | A real TypeScript repository can be analysed and its actual symbols, dependencies and findings navigated |
| **M3 Break My Code** | Property-based generation, fuzzing with corpus, shrinking and timeout/crash detection, mutation testing (operators: comparison, arithmetic, condition removal, constants, call removal), defect reproductions with re-run, campaign management | The system finds and reproduces a seeded defect from the bug corpus without being told where it is |
| **M4 Repair verification** | Patch ingestion, isolated candidate revision, baseline vs candidate comparison, reproduction re-execution, regression detection, verdicts (`VERIFIED_AGAINST_TESTS`, `REPRODUCTION_STILL_FAILS`, `REGRESSION_DETECTED`, `INCONCLUSIVE`, `INFRASTRUCTURE_FAILURE`), reports, CLI and CI gates | A known bad patch fails verification and the corpus's known fixes pass |
| **M5 Multi-language** | Kotlin analysis, OCaml syntax/analysis components, Fortran numerical verification, original COBOL fixtures, cross-language comparisons | Every claimed language capability builds, runs and is tested on its own |
| **M6 Advanced intelligence** | Provider-independent AI assistant (explanations, test ideas, repair proposals as untrusted input), OCaml symbolic engine with SMT integration (`PROVEN / DISPROVEN / UNKNOWN / TIMEOUT / UNSUPPORTED`), WebGL architecture graph, risk prioritisation | Results separate verified facts, uncertain inferences and AI suggestions |
| **M7 Commercial** | Plans, subscriptions, metering, quotas, billing, enterprise SSO, private deployment tooling, monitoring, backup/restore, hardening, benchmarks | Operational, security, tenant-isolation and deployment tests pass |

The bug corpus (`benchmarks/bug-corpus`) is a regression yardstick: every engine
must keep finding what it found before and stay quiet once the known fixes are
applied. It is **not** an independent accuracy benchmark. The M2 TypeScript rules
were written with knowledge of the seeded defects, so their perfect score on the
corpus says nothing about precision or recall on unseen code. Measuring that needs a
held-out corpus of original samples the rule authors have not seen (backlog N13).
