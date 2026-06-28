#!/usr/bin/env bash
set -euo pipefail

output_file="$(mktemp)"
root_output_file="$(mktemp)"
migrate_output_file="$(mktemp)"
trap 'rm -f "$output_file" "$root_output_file" "$migrate_output_file"' EXIT

./gradlew computeWorkspaceDependencies --dry-run --console=plain >"$output_file"

if grep -q 'ResolveDependencies SKIPPED' "$output_file"; then
  echo "Default computeWorkspaceDependencies still schedules legacy ResolveVariantDependenciesTask tasks." >&2
  grep 'ResolveDependencies SKIPPED' "$output_file" >&2
  exit 1
fi

if grep -q ':collectDeclaredDependencyMetadata SKIPPED' "$output_file"; then
  echo "Default computeWorkspaceDependencies still schedules single-task declared metadata aggregation." >&2
  exit 1
fi

grep -q ':collectSampleAndroidDeclaredDependencyMetadata SKIPPED' "$output_file"
grep -q ':mergeDeclaredDependencyMetadata SKIPPED' "$output_file"
grep -q ':collectKspProcessorDependencies SKIPPED' "$output_file"
grep -q ':resolveWorkspaceDependencies SKIPPED' "$output_file"
grep -q ':computeWorkspaceDependencies SKIPPED' "$output_file"

./gradlew generateRootBazelScripts --dry-run --console=plain >"$root_output_file"

if grep -q ':.*:generateBazelScripts SKIPPED' "$root_output_file"; then
  echo "generateRootBazelScripts still depends on project generateBazelScripts tasks." >&2
  grep ':.*:generateBazelScripts SKIPPED' "$root_output_file" >&2
  exit 1
fi

grep -q ':finalizeWorkspacePlan SKIPPED' "$root_output_file"
grep -q ':generateBuildifierScript SKIPPED' "$root_output_file"
grep -q ':generateRootBazelScripts SKIPPED' "$root_output_file"

line_number_in() {
  local file="$1"
  local pattern="$2"
  grep -n "$pattern" "$file" | head -n 1 | cut -d: -f1
}

root_buildifier_line="$(line_number_in "$root_output_file" ':generateBuildifierScript SKIPPED')"
root_generate_line="$(line_number_in "$root_output_file" ':generateRootBazelScripts SKIPPED')"

if [[ "$root_buildifier_line" -ge "$root_generate_line" ]]; then
  echo "generateBuildifierScript must run before generateRootBazelScripts." >&2
  exit 1
fi

./gradlew migrateToBazel --dry-run --console=plain >"$migrate_output_file"

if grep -E ':(formatBazelScripts|formatWorkSpace|formatBuildBazel) SKIPPED' "$migrate_output_file"; then
  echo "migrateToBazel still schedules removed standalone format tasks." >&2
  grep -E ':(formatBazelScripts|formatWorkSpace|formatBuildBazel) SKIPPED' "$migrate_output_file" >&2
  exit 1
fi

grep -q ':generateBuildifierScript SKIPPED' "$migrate_output_file"
grep -q ':generateRootBazelScripts SKIPPED' "$migrate_output_file"
grep -q ':pinMavenArtifacts SKIPPED' "$migrate_output_file"
grep -q ':.*:generateBazelScripts SKIPPED' "$migrate_output_file"
grep -q ':postScriptGenerateTask SKIPPED' "$migrate_output_file"
grep -q ':migrateToBazel SKIPPED' "$migrate_output_file"

line_number() {
  line_number_in "$migrate_output_file" "$1"
}

last_line_number() {
  grep -n "$1" "$migrate_output_file" | tail -n 1 | cut -d: -f1
}

buildifier_line="$(line_number ':generateBuildifierScript SKIPPED')"
root_generate_line="$(line_number ':generateRootBazelScripts SKIPPED')"
pin_line="$(line_number ':pinMavenArtifacts SKIPPED')"
first_project_generate_line="$(line_number ':.*:generateBazelScripts SKIPPED')"
last_project_generate_line="$(last_line_number ':.*:generateBazelScripts SKIPPED')"
post_script_line="$(line_number ':postScriptGenerateTask SKIPPED')"
migrate_line="$(line_number ':migrateToBazel SKIPPED')"

if [[ "$buildifier_line" -ge "$root_generate_line" ]]; then
  echo "generateBuildifierScript must run before generateRootBazelScripts." >&2
  exit 1
fi

if [[ "$root_generate_line" -ge "$pin_line" ]]; then
  echo "pinMavenArtifacts must run after generateRootBazelScripts." >&2
  exit 1
fi

if [[ "$buildifier_line" -ge "$first_project_generate_line" ]]; then
  echo "generateBuildifierScript must run before all project generateBazelScripts tasks." >&2
  exit 1
fi

if [[ "$last_project_generate_line" -ge "$post_script_line" ]]; then
  echo "postScriptGenerateTask must run after all project generateBazelScripts tasks." >&2
  exit 1
fi

if [[ "$pin_line" -ge "$migrate_line" || "$post_script_line" -ge "$migrate_line" ]]; then
  echo "migrateToBazel must run after pinMavenArtifacts and postScriptGenerateTask." >&2
  exit 1
fi
