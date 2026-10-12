#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
# Install both artifacts so the fixture exercises this checkout, including
# plugin loading and the library's packaged resources.
(cd "$repo_root/cloverage" && CLOJURE_VERSION=1.12 lein install)
(cd "$repo_root/lein-cloverage" && lein install)

cd "$repo_root/lein-cloverage/test-resources/integration"
rm -rf target/integration
lein cloverage --output target/integration/explicit --text --no-html
test -s target/integration/explicit/coverage.txt

CLOVERAGE_VERSION=1.3.0-SNAPSHOT lein with-profile +auto-version cloverage \
  --output target/integration/auto --text --no-html
test -s target/integration/auto/coverage.txt

if lein cloverage --output target/integration/threshold --text --no-html \
  --fail-threshold 100 > target/integration/threshold.log 2>&1; then
  cat target/integration/threshold.log
  echo 'Expected an unsuccessful exit when coverage is below the threshold.' >&2
  exit 1
fi
cat target/integration/threshold.log
test -s target/integration/threshold/coverage.txt
grep -q 'coverage is below threshold' target/integration/threshold.log

echo 'Plugin integration checks passed.'
