#!/usr/bin/env bash
set -eo pipefail

FILE="${1:-}"

if [[ -z "$FILE" ]]; then
  TMP_NOTES="$(mktemp)"
  bash scripts/generate_release_notes.sh "v2.8.0" "v2.7.7" "Amal-kphilip/Geekify-App" > "$TMP_NOTES"
  FILE="$TMP_NOTES"
fi

if [[ ! -f "$FILE" ]]; then
  echo "Error: Release notes file '$FILE' not found."
  exit 1
fi

echo "Checking release notes file: $FILE"

# 1. Check title
if ! grep -qE '^# Geekify v[0-9]+\.[0-9]+.* - Release Notes' "$FILE"; then
  echo "Error: Missing or invalid title line."
  exit 1
fi

# 2. Check release date
if ! grep -qE '^\*\*Release Date:\*\* [0-9]{1,2} [A-Z][a-z]+ [0-9]{4}' "$FILE"; then
  echo "Error: Missing or invalid Release Date line."
  exit 1
fi

# 3. Check section headers (only allowed: What's New, Improvements, Bug Fixes)
INVALID_HEADERS="$(grep -E '^### ' "$FILE" | grep -vE '^### (What'\''s New|Improvements|Bug Fixes)$' || true)"
if [[ -n "$INVALID_HEADERS" ]]; then
  echo "Error: Unrecognized section headers found:"
  echo "$INVALID_HEADERS"
  exit 1
fi

# 4. Check bullets for commit hashes, PR numbers, or links
BULLETS="$(grep -E '^- ' "$FILE" || true)"
if [[ -n "$BULLETS" ]]; then
  # Check for raw hashes like (abc1234)
  if echo "$BULLETS" | grep -E '\([0-9a-f]{7,40}\)' > /dev/null 2>&1; then
    echo "Error: Bullets contain commit hashes."
    exit 1
  fi
  # Check for PR numbers like (#42)
  if echo "$BULLETS" | grep -E '\(#[0-9]+\)' > /dev/null 2>&1; then
    echo "Error: Bullets contain PR numbers."
    exit 1
  fi
  # Check for URLs in bullets
  if echo "$BULLETS" | grep -E 'https?://' > /dev/null 2>&1; then
    echo "Error: Bullets contain URLs."
    exit 1
  fi
fi

# 5. Check footer
if ! grep -qE '^\*\*Geekify Team\*\*' "$FILE"; then
  echo "Error: Missing '**Geekify Team**' in footer."
  exit 1
fi

echo "✅ Release notes validation passed: format, sections, bullets, and boilerplate are all valid."
