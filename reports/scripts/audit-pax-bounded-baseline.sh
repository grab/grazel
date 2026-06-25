#!/usr/bin/env bash
set -euo pipefail

pax_root="${PAX_ROOT:-/Users/arun.sampathkumar/work/pax-android}"
output_file="${1:-}"

cd "$pax_root"

build_file="app/BUILD.bazel"
workspace_file="WORKSPACE"

if [[ ! -f "$build_file" ]]; then
  echo "Missing $pax_root/$build_file. Run ./gradlew migrateToBazel first." >&2
  exit 1
fi

if [[ ! -f "$workspace_file" ]]; then
  echo "Missing $pax_root/$workspace_file. Run ./gradlew migrateToBazel first." >&2
  exit 1
fi

target_block() {
  local target="$1"
  awk -v target="$target" '
    /^[a-zA-Z_]+\(/{ in_rule = 1; block = $0 "\n"; next }
    in_rule {
      block = block $0 "\n"
      if ($0 == ")") {
        if (block ~ "name = \"" target "\"") {
          print block
        }
        in_rule = 0
        block = ""
      }
    }
  ' "$build_file"
}

attr_block() {
  local attr="$1"
  awk -v attr="$attr" '
    $0 ~ "^[[:space:]]*" attr " = \\[" { in_attr = 1; next }
    in_attr && $0 ~ "^[[:space:]]*\\]," { in_attr = 0; next }
    in_attr { print }
  '
}

labels_from_attr() {
  local attr="$1"
  attr_block "$attr" | sed -n 's/^[[:space:]]*"\([^"]*\)",[[:space:]]*$/\1/p'
}

normalize_maven_label() {
  sed -E 's#^@[A-Za-z0-9_]+_maven//:#@maven//:#'
}

audit_target() {
  local target="$1"
  local block
  block="$(target_block "$target")"
  if [[ -z "$block" ]]; then
    echo "Missing target app:$target" >&2
    exit 1
  fi

  local deps tags dep_count tag_count debug_dep_count android_test_dep_count non_maven_tags missing_direct_tags
  deps="$(labels_from_attr deps <<<"$block" | sort -u)"
  tags="$(labels_from_attr tags <<<"$block" | sort -u)"

  dep_count="$(wc -l <<<"$deps" | tr -d ' ')"
  tag_count="$(wc -l <<<"$tags" | tr -d ' ')"
  debug_dep_count="$(grep -c '^@debug_maven//:' <<<"$deps" || true)"
  android_test_dep_count="$(grep -c '^@android_test_maven//:' <<<"$deps" || true)"

  non_maven_tags="$(grep '^@' <<<"$tags" | grep -v '^@maven//:' || true)"
  if [[ -n "$non_maven_tags" ]]; then
    echo "Target app:$target has non-@maven compile-filter tags:" >&2
    echo "$non_maven_tags" >&2
    exit 1
  fi

  missing_direct_tags="$(
    grep '^@[A-Za-z0-9_]*maven//:' <<<"$deps" |
      normalize_maven_label |
      sort -u |
      comm -23 - <(printf '%s\n' "$tags" | sort -u) || true
  )"
  if [[ -n "$missing_direct_tags" ]]; then
    echo "Target app:$target is missing @maven tags for direct Maven deps:" >&2
    echo "$missing_direct_tags" >&2
    exit 1
  fi

  {
    echo "### //$target"
    echo
    echo "- deps: $dep_count"
    echo "- tags: $tag_count"
    echo "- @debug_maven deps: $debug_dep_count"
    echo "- @android_test_maven deps: $android_test_dep_count"
    echo "- tag shape: all tags use @maven//:"
    echo "- direct Maven deps: normalized @maven tag present for each direct Maven dep"
    echo
  } >>"$audit_tmp"
}

audit_tmp="$(mktemp)"
trap 'rm -f "$audit_tmp"' EXIT

{
  echo "# PAX Bounded Audit Baseline"
  echo
  echo "- PAX root: \`$pax_root\`"
  echo "- Generated from: \`reports/scripts/audit-pax-bounded-baseline.sh\`"
  echo "- Expected precondition: run \`./gradlew migrateToBazel --no-daemon --console=plain --stacktrace\` in PAX first."
  echo
  echo "## Target Counts And Tag Shape"
  echo
} >"$audit_tmp"

audit_target "app-gps-pax-debug"
audit_target "app-gps-pax-debug-android-test"

{
  echo "## Strict Reachability Spot Check"
  echo
  if [[ -f "bug-report-kit-implementation/BUILD.bazel" ]]; then
    echo "- bug-report-kit-implementation/BUILD.bazel: present"
    echo "  - Note: if this module is unreachable from the configured root apps, this should become absent or ignored in the reachability cleanup."
  elif [[ -f "bug-report-kit-implementation/BUILD.bazelignore" ]]; then
    echo "- bug-report-kit-implementation/BUILD.bazelignore: present"
  else
    echo "- bug-report-kit-implementation active BUILD output: absent"
  fi
  echo
  echo "## Workspace Shape"
  echo
  echo "- WORKSPACE lines: $(wc -l <"$workspace_file" | tr -d ' ')"
  echo "- maven_install entries: $(grep -c 'maven_install(' "$workspace_file" || true)"
}

if [[ -n "$output_file" ]]; then
  mkdir -p "$(dirname "$output_file")"
  cp "$audit_tmp" "$output_file"
else
  cat "$audit_tmp"
fi
