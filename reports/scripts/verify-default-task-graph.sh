#!/usr/bin/env bash
set -euo pipefail

output_file="$(mktemp)"
trap 'rm -f "$output_file"' EXIT

./gradlew computeWorkspaceDependencies --dry-run --console=plain >"$output_file"

if grep -q 'ResolveDependencies SKIPPED' "$output_file"; then
  echo "Default computeWorkspaceDependencies still schedules legacy ResolveVariantDependenciesTask tasks." >&2
  grep 'ResolveDependencies SKIPPED' "$output_file" >&2
  exit 1
fi

grep -q ':collectDeclaredDependencyMetadata SKIPPED' "$output_file"
grep -q ':collectKspProcessorDependencies SKIPPED' "$output_file"
grep -q ':computeWorkspaceDependencies SKIPPED' "$output_file"
