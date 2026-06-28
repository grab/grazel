#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
INVENTORY="${JSON_PHASE_INVENTORY:-${REPO_ROOT}/reports/specs/execution-log/item30-json-phase-inventory.tsv}"
PATTERN='Json\.encodeToString|Json\.decodeFromString|fromJson|writeJson|encodeToStream|decodeFromStream'

if [[ ! -f "${INVENTORY}" ]]; then
  echo "Missing JSON phase inventory: ${INVENTORY}" >&2
  exit 1
fi

ACTUAL="$(mktemp)"
LISTED="$(mktemp)"
MISSING="$(mktemp)"
STALE="$(mktemp)"
trap 'rm -f "${ACTUAL}" "${LISTED}" "${MISSING}" "${STALE}"' EXIT

cd "${REPO_ROOT}"

rg -n "${PATTERN}" grazel-gradle-plugin/src/main/kotlin -S \
  | awk -F: '$3 !~ /^[[:space:]]*import[[:space:]]/ { print $1 "\t" $2 }' \
  | sort -u > "${ACTUAL}"

tail -n +2 "${INVENTORY}" \
  | awk -F '\t' 'NF >= 2 && $1 !~ /^#/ { print $1 "\t" $2 }' \
  | sort -u > "${LISTED}"

comm -23 "${ACTUAL}" "${LISTED}" > "${MISSING}"
comm -13 "${ACTUAL}" "${LISTED}" > "${STALE}"

if [[ -s "${MISSING}" ]]; then
  echo "JSON encode/decode call sites missing from ${INVENTORY}:" >&2
  cat "${MISSING}" >&2
  exit 1
fi

if [[ -s "${STALE}" ]]; then
  echo "Stale JSON inventory rows in ${INVENTORY}:" >&2
  cat "${STALE}" >&2
  exit 1
fi

echo "JSON phase inventory is current."
