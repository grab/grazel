#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
output_file="${1:-$repo_root/reports/specs/source-shape-inventory.tsv}"
tmp_file="$(mktemp)"
existing_file=""
trap 'rm -f "$tmp_file"' EXIT

if [[ "${SOURCE_SHAPE_IGNORE_EXISTING:-false}" != "true" && -f "$output_file" ]]; then
  existing_file="$output_file"
fi

area_for_file() {
  local file="$1"
  case "$file" in
    *"/src/main/"*) echo "main" ;;
    *"/src/test/"*) echo "test" ;;
    *"/src/functionalTest/"*) echo "functionalTest" ;;
    *"/build-logic/"*) echo "build_logic" ;;
    *) echo "other" ;;
  esac
}

detect_patterns() {
  local file="$1"
  local absolute_file="$repo_root/$file"
  local patterns=()

  if rg --quiet 'private[[:space:]]+fun[[:space:]]+[^.(]*(MutableMap|Map|Set|Collection|List|Iterable|Sequence)<.*>\.' "$absolute_file"; then
    patterns+=("generic_collection_receiver")
  fi
  if rg --quiet 'private[[:space:]]+fun[[:space:]]+([A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*<[^>]+>)\.[A-Za-z_]' "$absolute_file"; then
    patterns+=("domain_receiver_extension")
  fi
  if rg --quiet '\b(val|var)[[:space:]]+(<[^>]+>[[:space:]]+)?(MutableMap|Map|Set|Collection|List|Iterable|Sequence)<.*>\.' "$absolute_file"; then
    patterns+=("generic_collection_receiver")
  fi
  if rg --quiet 'fun[[:space:]]+(@receiver:[A-Za-z0-9_]+[[:space:]]+)?Project\.' "$absolute_file"; then
    patterns+=("project_extension")
  fi
  if rg --quiet 'private[[:space:]]+(data[[:space:]]+)?class[[:space:]]+[A-Za-z0-9_]*(Input|Result|State|Plan|Key|Edge|Node|Summary)\b' "$absolute_file"; then
    patterns+=("private_helper_model")
  fi
  if rg --quiet 'getDeclared|setAccessible|java\.lang\.reflect|Proxy\.|::class\.java' "$absolute_file"; then
    patterns+=("reflection_or_dynamic_access")
  fi
  if rg --quiet '@Suppress\("UNCHECKED_CAST"\)|[[:space:]]as[[:space:]]+[A-Za-z0-9_<*,.? ]+' "$absolute_file"; then
    patterns+=("unchecked_cast")
  fi
  if rg --quiet 'readText\(|assert[^(\n]*\([^)]*contains\(|contains\("' "$absolute_file"; then
    patterns+=("source_string_assertion")
  fi
  if rg --quiet 'Codex|Claude|LLM|AI-generated|context rot|migration diary|temporary|TODO|FIXME' "$absolute_file"; then
    patterns+=("comment_or_context_artifact")
  fi
  if rg --quiet '\b(class|data[[:space:]]+class|object|interface)[[:space:]]+[A-Za-z0-9_]*(Data|Info|Context|Holder|Wrapper|Utils)\b' "$absolute_file"; then
    patterns+=("generic_model_name")
  fi
  if rg --quiet 'substringAfter|substringBefore|removePrefix|removeSuffix|endsWith\(|startsWith\(|JsonObject|Property<String>|ListProperty<String>|SetProperty<String>' "$absolute_file"; then
    patterns+=("type_boundary_candidate")
  fi

  if [[ "${#patterns[@]}" -eq 0 ]]; then
    echo "none"
  else
    local IFS=","
    echo "${patterns[*]}"
  fi
}

existing_tail_for_file() {
  local file="$1"
  if [[ -z "$existing_file" ]]; then
    return 1
  fi
  awk -F '\t' -v file="$file" '
    function field(name) {
      return (name in col) ? $col[name] : ""
    }
    function fallback(primary, secondary) {
      value = field(primary)
      if (value != "") return value
      return field(secondary)
    }
    NR > 1 && $1 == file {
      owner_agent = field("owner_agent")
      review_status = field("review_status")
      receiver_extension_status = fallback("receiver_extension_status", "generic_receiver_status")
      helper_model_status = field("helper_model_status")
      naming_status = field("naming_status")
      type_boundary_status = fallback("type_boundary_status", "test_escape_status")
      test_quality_status = fallback("test_quality_status", "test_escape_status")
      comment_status = field("comment_status")
      action_taken = field("action_taken")
      retained_rationale = field("retained_rationale")
      verification = field("verification")

      sep = "\034"
      printf "%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s", \
        owner_agent, sep, review_status, sep, receiver_extension_status, sep, helper_model_status, sep, \
        naming_status, sep, type_boundary_status, sep, test_quality_status, sep, comment_status, sep, \
        action_taken, sep, retained_rationale, sep, verification
      found = 1
      exit
    }
    NR == 1 {
      for (i = 1; i <= NF; i++) {
        col[$i] = i
      }
      next
    }
    END { if (!found) exit 1 }
  ' "$existing_file"
}

changed_kotlin_files() {
  {
    git -C "$repo_root" diff --name-only --diff-filter=ACMR master...HEAD -- '*.kt'
    git -C "$repo_root" diff --name-only --diff-filter=ACMR HEAD -- '*.kt'
    git -C "$repo_root" diff --name-only --cached --diff-filter=ACMR HEAD -- '*.kt'
    git -C "$repo_root" ls-files --others --exclude-standard -- '*.kt'
  } | sort -u
}

{
  printf "file\tarea\towner_agent\treview_status\treceiver_extension_status\thelper_model_status\tnaming_status\ttype_boundary_status\ttest_quality_status\tcomment_status\taction_taken\tretained_rationale\tverification\tdetected_patterns\n"

  changed_kotlin_files | while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    [[ -f "$repo_root/$file" ]] || continue

    area="$(area_for_file "$file")"
    detected_patterns="$(detect_patterns "$file")"

    if existing_tail="$(existing_tail_for_file "$file")"; then
      IFS=$'\034' read -r owner_agent review_status receiver_extension_status helper_model_status naming_status type_boundary_status test_quality_status comment_status action_taken retained_rationale verification <<<"$existing_tail"

      if [[ -z "$naming_status" ]]; then
        naming_status="pending"
      fi
      if [[ -z "$type_boundary_status" ]]; then
        type_boundary_status="pending"
      fi
      if [[ -z "$test_quality_status" ]]; then
        test_quality_status="pending"
      fi
    else
      owner_agent="unassigned"
      review_status="pending"
      receiver_extension_status="pending"
      helper_model_status="pending"
      naming_status="pending"
      type_boundary_status="pending"
      test_quality_status="pending"
      comment_status="pending"
      action_taken="pending"
      retained_rationale="pending"
      verification="pending"
    fi

    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
      "$file" \
      "$area" \
      "$owner_agent" \
      "$review_status" \
      "$receiver_extension_status" \
      "$helper_model_status" \
      "$naming_status" \
      "$type_boundary_status" \
      "$test_quality_status" \
      "$comment_status" \
      "$action_taken" \
      "$retained_rationale" \
      "$verification" \
      "$detected_patterns"
  done
} > "$tmp_file"

mv "$tmp_file" "$output_file"
trap - EXIT
echo "Wrote $output_file"
