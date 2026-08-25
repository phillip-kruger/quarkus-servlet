#!/usr/bin/env bash
# Fails when the Servlet TCK reports more errors than the agreed baseline, or when it did not
# actually run to completion.
#
# The suite runs with no exclusions and does not pass yet, so a plain pass/fail gate would be
# permanently red and would stop meaning anything. Comparing against a baseline catches the case
# that matters - a change making things worse - and nags when the baseline is stale.
#
# The completeness check is not belt-and-braces. Booting ~200 Quarkus applications in one JVM is
# heavy enough that the fork sometimes dies partway through ("terminated without properly saying
# goodbye"), and a truncated run reports *fewer* errors simply because it ran fewer tests. Summing
# errors alone, that reads as an improvement. Asserting the test count too is what tells a green
# build apart from a build that gave up early.
set -euo pipefail

baseline="${1:?usage: check-tck-errors.sh <baseline-error-count> [expected-test-count]}"
expected_tests="${2:-1714}"
report_dir="tck/target/failsafe-reports"

if [ ! -d "$report_dir" ]; then
  echo "::error::No TCK reports at $report_dir - the suite did not run." >&2
  exit 1
fi

sum_attribute() {
  grep -ho "$1=\"[0-9]*\"" "$report_dir"/TEST-*.xml | grep -o '[0-9]*' | paste -sd+ - | bc
}

errors=$(sum_attribute errors)
tests=$(sum_attribute tests)

echo "TCK: $tests tests, $errors errors (baseline $baseline errors / $expected_tests tests)"

if [ "$tests" -lt "$expected_tests" ]; then
  echo "::error::Only $tests of $expected_tests tests ran - the suite did not complete, so its" \
       "error count means nothing. Check the surefire output for a crashed fork." >&2
  exit 1
fi

if [ "$errors" -gt "$baseline" ]; then
  echo "::error::The TCK regressed: $errors errors against a baseline of $baseline." >&2
  exit 1
fi

if [ "$errors" -lt "$baseline" ]; then
  echo "::notice::The TCK improved to $errors errors. Lower the baseline in build.yml to lock it in."
fi
