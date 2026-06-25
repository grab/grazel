#!/usr/bin/env bash
set -euo pipefail

run_migrate=true
if [[ "${1:-}" == "--skip-migrate" ]]; then
  run_migrate=false
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if [[ "$run_migrate" == true ]]; then
  ./gradlew migrateToBazel --console=plain
fi

reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh

generated_files=()
while IFS= read -r generated_file; do
  generated_files+=("$generated_file")
done < <(
  git ls-files \
    BUILD.bazel \
    WORKSPACE \
    '*_maven_install.json' \
    '*_install.json' \
    '*/BUILD.bazel' |
    sort
)

if [[ "${#generated_files[@]}" -eq 0 ]]; then
  echo "No tracked generated files found for golden baseline diff." >&2
  exit 1
fi

git diff --exit-code -- "${generated_files[@]}"

echo "Grazel golden baseline verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean."
