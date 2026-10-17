#!/usr/bin/env bash
# Checks that every seeded sample still behaves as documented in its DEFECTS.md:
# the shipped tree fails exactly its regression tests, and applying all fix
# patches makes the whole suite pass.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sample="$root/benchmarks/bug-corpus/sample-commerce-api"
expected_failures=6

run_suite() {
  (cd "$1" && node --experimental-strip-types --test --test-reporter=tap "tests/**/*.test.ts" 2>/dev/null || true) \
    | awk '/^# (pass|fail) /{print $2"="$3}'
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

shipped="$(run_suite "$sample" | tr '\n' ' ')"
echo "shipped: $shipped"
[[ "$shipped" == *"fail=$expected_failures"* ]] || { echo "expected $expected_failures failing tests in the shipped sample"; exit 1; }

cp -R "$sample" "$work/fixed"
(cd "$work/fixed" && git apply fixes/*.patch)
fixed="$(run_suite "$work/fixed" | tr '\n' ' ')"
echo "fixed:   $fixed"
[[ "$fixed" == *"fail=0"* ]] || { echo "fix patches should make every test pass"; exit 1; }
echo "bug corpus OK"
