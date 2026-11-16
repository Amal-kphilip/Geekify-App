#!/usr/bin/env bash
set -eo pipefail

clean_subject() {
  local line="$1"
  line="$(echo "$line" | sed -E "s/^[a-zA-Z]+(\([^)]+\))?:[[:space:]]*//")"
  line="$(echo "$line" | sed -E "s/[[:space:]]*\((#[0-9]+|[0-9a-f]{7,40})\)[[:space:]]*$//")"
  line="$(echo "$line" | sed -E "s/^[[:space:]]+//;s/[[:space:]]+$//")"
  if [[ -n "$line" ]]; then
    local first="$(echo "${line:0:1}" | tr "[:lower:]" "[:upper:]")"
    echo "- ${first}${line:1}"
  fi
}

generate_notes() {
  local tag="$1"
  local prev_tag="$2"
  local repo="$3"
  local commits="$4"

  local release_date
  release_date="$(date -u "+%-d %B %Y" 2>/dev/null || date -u "+%d %B %Y" | sed "s/^0//")"

  local feat_lines=""
  local improve_lines=""
  local fix_lines=""

  while IFS= read -r subject || [[ -n "$subject" ]]; do
    [[ -z "$subject" ]] && continue
    if echo "$subject" | grep -iE "^(chore|docs|test|ci|build|revert)(\([^)]+\))?:|^Merge |^[Bb]ump version|^[Rr]elease v" > /dev/null 2>&1; then
      continue
    fi

    local cleaned
    cleaned="$(clean_subject "$subject")"
    [[ -z "$cleaned" ]] && continue

    if echo "$subject" | grep -iE "^feat(\([^)]+\))?:" > /dev/null 2>&1; then
      feat_lines="${feat_lines}${cleaned}"$'\n'
    elif echo "$subject" | grep -iE "^fix(\([^)]+\))?:" > /dev/null 2>&1; then
      fix_lines="${fix_lines}${cleaned}"$'\n'
    else
      improve_lines="${improve_lines}${cleaned}"$'\n'
    fi
  done <<< "$commits"

  echo "# Geekify ${tag} - Release Notes"
  echo ""
  echo "**Release Date:** ${release_date}"
  echo ""
  if [[ -n "$feat_lines" ]]; then
    echo "### What's New"
    printf "%s" "$feat_lines"
    echo ""
  fi
  if [[ -n "$improve_lines" ]]; then
    echo "### Improvements"
    printf "%s" "$improve_lines"
    echo ""
  fi
  if [[ -n "$fix_lines" ]]; then
    echo "### Bug Fixes"
    printf "%s" "$fix_lines"
    echo ""
  fi
  echo "---"
  echo ""
  echo "**Geekify Team**"
  if [[ -n "$prev_tag" ]]; then
    echo "**Full Changelog**: https://github.com/${repo}/compare/${prev_tag}...${tag}"
  fi
}

# If run directly with arguments
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  TAG="${1:-v2.8.0}"
  PREV_TAG="${2:-v2.7.7}"
  REPO="${3:-Amal-kphilip/Geekify-App}"

  # If override exists, use it
  if [[ -f "release-notes/${TAG}.md" ]]; then
    cat "release-notes/${TAG}.md"
  else
    if [[ -n "$PREV_TAG" ]]; then
      COMMITS="$(git log "${PREV_TAG}..HEAD" --no-merges --pretty=format:'%s' 2>/dev/null || true)"
    else
      COMMITS="$(git log -n 10 --no-merges --pretty=format:'%s' 2>/dev/null || true)"
    fi
    generate_notes "$TAG" "$PREV_TAG" "$REPO" "$COMMITS"
  fi
fi
