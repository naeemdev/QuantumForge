# QuantumForge — Product Vision

**Break Your Code. Prove Your Fix. Ship with Confidence.**

QuantumForge is a multi-tenant platform for analysing, testing, breaking and
verifying source code — whether a person or a coding assistant wrote it. It
pulls together things that usually live in separate tools: source intelligence,
static analysis, isolated test execution, automated defect discovery (property
testing, fuzzing, mutation testing, fault injection), repair verification and
evidence reporting.

## The problem

Code assistants produce a lot of plausible-looking code fast. That code can carry
wrong business logic, security holes, concurrency bugs, missing edge cases, bad
retry behaviour, numeric precision errors and regressions. Reviewers need
**evidence**, not opinions: a reproducible failing input, the exact revision, the
runtime it failed on, and proof that a proposed fix makes that reproduction pass
without breaking anything else that was tested.

## Core promise

1. Sign in, create an organization, create a project.
2. Import an immutable source snapshot (archive upload or Git import).
3. Understand the code: languages, files, symbols, dependencies.
4. Run analyses and tests in isolated workers.
5. Actively try to break the code and keep reproducible failures.
6. Submit a fix (manual or AI-proposed) and verify it against the reproduction
   and the regression suite.
7. Export an evidence report and wire the same checks into CI.

## Honesty rules (apply everywhere in the product)

Every claim QuantumForge shows falls into one of four buckets:

| Bucket        | Meaning                                                                 |
|---------------|-------------------------------------------------------------------------|
| Proven        | A stated property was formally verified under documented assumptions.   |
| Test-verified | Supported by specific tests and stored execution evidence.              |
| Suspected     | Analysis points at a possible problem that has not been reproduced.     |
| Unknown       | Not enough evidence to say.                                             |

- Passing tests never mean "bug-free". Reports say what was checked, not more.
- Surviving mutants are test-suite gaps, not defects in the original program.
- A defect is only "confirmed" with a stored, re-runnable reproduction.
- A module that has no backend does not appear as working in the UI.
- AI output is a proposal. It never bypasses the sandbox, authorization or human review.

## Users

Individual developers, development teams, engineering managers, security
engineers, AI engineering teams evaluating coding agents, and enterprise
administrators running a private deployment.

## Defining features

**Break My Code** and **Repair Verification** are the two features the product is
built around. Everything else (intelligence, static analysis, the job engine,
evidence storage) exists to make those two trustworthy.

## Deployment

The same application runs as multi-tenant cloud SaaS or as a self-hosted
enterprise install. The editions differ in infrastructure configuration
(identity provider, object storage, worker isolation, scaling), not in code.

See `ROADMAP.md` for milestone order and `PROGRESS.md` for what is actually built.
