#!/usr/bin/env bash
set -eo pipefail

source scripts/generate_release_notes.sh

echo "=== TEST 1: Sample commits ==="
SAMPLE=$(cat << 'EOF'
feat(home): pinned Home header (#42)
perf(ascii): offload simulation to background thread (1a2b3c4)
fix(search): clear recent searches on logout
chore: bump version
Merge branch 'main' of github.com
random un-prefixed commit
EOF
)
generate_notes "v2.8.0" "v2.7.7" "Amal-kphilip/Geekify-App" "$SAMPLE"

echo ""
echo "=== TEST 2: Only chore commits ==="
CHORE_SAMPLE=$(cat << 'EOF'
chore: bump version
ci: update workflow
docs: update readme
EOF
)
generate_notes "v2.8.0" "v2.7.7" "Amal-kphilip/Geekify-App" "$CHORE_SAMPLE"

echo ""
echo "=== TEST 3: Empty range ==="
generate_notes "v2.8.0" "v2.7.7" "Amal-kphilip/Geekify-App" ""
