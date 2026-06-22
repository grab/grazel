#!/usr/bin/env bash
set -euo pipefail

build_file="sample-android/BUILD.bazel"
sample_test_build_file="sample-android-tests/BUILD.bazel"

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

target_block_from_file() {
  local file="$1"
  local target="$2"
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
  ' "$file"
}

target_block() {
  local target="$1"
  target_block_from_file "$build_file" "$target"
}

require_target_label() {
  local target="$1"
  local label="$2"
  if ! target_block "$target" | grep -q "\"$label\""; then
    echo "Missing expected label in $target: $label" >&2
    exit 1
  fi
}

reject_target_label() {
  local target="$1"
  local label="$2"
  if target_block "$target" | grep -q "\"$label\""; then
    echo "Found unexpected label in $target: $label" >&2
    exit 1
  fi
}

require_target_label_in_file() {
  local file="$1"
  local target="$2"
  local label="$3"
  if ! target_block_from_file "$file" "$target" | grep -q "\"$label\""; then
    echo "Missing expected label in $file target $target: $label" >&2
    exit 1
  fi
}

reject_target_label_in_file() {
  local file="$1"
  local target="$2"
  local label="$3"
  if target_block_from_file "$file" "$target" | grep -q "\"$label\""; then
    echo "Found unexpected label in $file target $target: $label" >&2
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

for target in sample-android-demo-free-debug sample-android-full-free-debug; do
  require_target_label "$target" "@maven//:androidx_constraintlayout_constraintlayout"
  reject_target_label "$target" "@free_maven//:androidx_constraintlayout_constraintlayout"
  reject_target_label "$target" "@paid_maven//:androidx_constraintlayout_constraintlayout"
done

for target in sample-android-demo-paid-debug sample-android-full-paid-debug; do
  require_target_label "$target" "@maven//:androidx_constraintlayout_constraintlayout"
  reject_target_label "$target" "@paid_maven//:androidx_constraintlayout_constraintlayout"
  reject_target_label "$target" "@free_maven//:androidx_constraintlayout_constraintlayout"
done

require_label "@android_test_maven//:androidx_test_monitor"
reject_label "@maven//:androidx_test_monitor"
reject_label "@android_test_maven//:androidx_core_core"

for target in \
  sample-android-tests-demo-free-debug \
  sample-android-tests-demo-paid-debug \
  sample-android-tests-full-free-debug \
  sample-android-tests-full-paid-debug; do
  require_target_label_in_file "$sample_test_build_file" "$target" "@android_test_maven//:androidx_test_runner"
  require_target_label_in_file "$sample_test_build_file" "$target" "@android_test_maven//:androidx_test_rules"
  require_target_label_in_file "$sample_test_build_file" "$target" "@android_test_maven//:androidx_compose_ui_ui_test_junit4"
  reject_target_label_in_file "$sample_test_build_file" "$target" "@maven//:androidx_test_runner"
  reject_target_label_in_file "$sample_test_build_file" "$target" "@maven//:androidx_test_rules"
  reject_target_label_in_file "$sample_test_build_file" "$target" "@maven//:androidx_compose_ui_ui_test_junit4"
done

lint_maven_block="$(awk '/name = "lint_maven"/,/^)/' WORKSPACE)"
lint_auto_service_block="$(awk '
  /maven\.artifact\(/ {
    in_artifact = 1
    block = ""
  }
  in_artifact {
    block = block $0 "\n"
  }
  in_artifact && /^        \),/ {
    if (block ~ /group = "com.google.auto.service"/ && block ~ /artifact = "auto-service-annotations"/) {
      print block
    }
    in_artifact = 0
  }
' <<<"$lint_maven_block")"
if ! grep -q '"com.google.auto.service:auto-service-annotations:1.1.1"' <<<"$lint_maven_block" &&
  ! grep -q 'version = "1.1.1"' <<<"$lint_auto_service_block"; then
  echo "lint_maven must keep Gradle-selected auto-service-annotations:1.1.1" >&2
  exit 1
fi
if grep -q '"com.google.auto.service:auto-service-annotations:1.0"' <<<"$lint_maven_block" ||
  grep -q 'version = "1.0"' <<<"$lint_auto_service_block"; then
  echo "lint_maven downgraded auto-service-annotations to 1.0" >&2
  exit 1
fi

if ! grep -q 'artifact = "constraintlayout"' WORKSPACE ||
  ! grep -q '"androidx.appcompat:appcompat"' WORKSPACE; then
  echo "WORKSPACE must preserve Gradle exclude rules for androidx.constraintlayout:constraintlayout" >&2
  exit 1
fi

actual_buckets="$(jq -r '.result | keys[]' build/grazel/dependencies.json | sort | paste -sd ',' -)"
expected_buckets="androidTest,debug,default,lint,test"
if [[ "$actual_buckets" != "$expected_buckets" ]]; then
  echo "Unexpected dependency buckets: $actual_buckets" >&2
  exit 1
fi

if ! jq -e \
  '.result.debug[]? | select(.shortId == "androidx.paging:paging-runtime" and .direct == true)' \
  build/grazel/dependencies.json >/dev/null; then
  echo "debug bucket must own debug-only paging dependency directly" >&2
  exit 1
fi

for bucket in default test lint; do
  if jq -e --arg bucket "$bucket" \
    '.result[$bucket][]? | select(.shortId == "androidx.paging:paging-runtime" and .direct == true)' \
    build/grazel/dependencies.json >/dev/null; then
    echo "Found debug-only paging dependency as direct dependency in $bucket bucket" >&2
    exit 1
  fi
done
