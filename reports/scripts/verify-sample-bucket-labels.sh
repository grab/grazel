#!/usr/bin/env bash
set -euo pipefail

build_file="sample-android/BUILD.bazel"

require_label() {
  local label="$1"
  if ! grep -q "\"$label\"" "$build_file"; then
    echo "Missing expected label in $build_file: $label" >&2
    exit 1
  fi
}

reject_label() {
  local label="$1"
  if grep -q "\"$label\"" "$build_file"; then
    echo "Found unexpected label in $build_file: $label" >&2
    exit 1
  fi
}

require_label "@debug_maven//:androidx_paging_paging_runtime"
reject_label "@maven//:androidx_paging_paging_runtime"

require_label "@maven//:androidx_activity_activity"
require_label "@maven//:androidx_compose_ui_ui"
require_label "@maven//:androidx_emoji2_emoji2"
require_label "@maven//:androidx_core_core"
require_label "@maven//:androidx_lifecycle_lifecycle_common"
require_label "@maven//:androidx_lifecycle_lifecycle_runtime"
require_label "@maven//:androidx_lifecycle_lifecycle_viewmodel"

reject_label "@debug_maven//:androidx_core_core"
reject_label "@debug_maven//:androidx_lifecycle_lifecycle_common"
reject_label "@debug_maven//:androidx_lifecycle_lifecycle_runtime"
reject_label "@debug_maven//:androidx_lifecycle_lifecycle_viewmodel"

reject_label "@demo_free_debug_maven//:androidx_activity_activity"
reject_label "@demo_free_debug_maven//:androidx_compose_ui_ui"
reject_label "@demo_free_debug_maven//:androidx_emoji2_emoji2"

require_label "@android_test_maven//:androidx_test_monitor"
reject_label "@maven//:androidx_test_monitor"

lint_maven_block="$(awk '/name = "lint_maven"/,/^)/' WORKSPACE)"
if ! grep -q '"com.google.auto.service:auto-service-annotations:1.1.1"' <<<"$lint_maven_block"; then
  echo "lint_maven must keep Gradle-selected auto-service-annotations:1.1.1" >&2
  exit 1
fi
if grep -q '"com.google.auto.service:auto-service-annotations:1.0"' <<<"$lint_maven_block"; then
  echo "lint_maven downgraded auto-service-annotations to 1.0" >&2
  exit 1
fi

if ! grep -q 'artifact = "constraintlayout"' WORKSPACE ||
  ! grep -q '"androidx.appcompat:appcompat"' WORKSPACE; then
  echo "WORKSPACE must preserve Gradle exclude rules for androidx.constraintlayout:constraintlayout" >&2
  exit 1
fi

for bucket in demo free full paid; do
  if jq -e --arg bucket "$bucket" \
    '.result[$bucket][]? | select(.shortId == "androidx.paging:paging-runtime" and .direct == true)' \
    build/grazel/dependencies.json >/dev/null; then
    echo "Found debug-only paging dependency as direct dependency in $bucket bucket" >&2
    exit 1
  fi
done
