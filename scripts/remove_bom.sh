#!/usr/bin/env bash
# Helper: remove UTF-8 BOM from files matching patterns
set -euo pipefail

find . -type f \( -name "*.kt" -o -name "gradle.properties" \) -print0 | \
  xargs -0 -n1 bash -c 'file="$0"; head -c 3 "$file" | xxd -p -c3 | grep -qi "efbbbf" && echo "Removing BOM from $file" && tail -c +4 "$file" > "$file.tmp" && mv "$file.tmp" "$file" || true' 

chmod +x scripts/remove_bom.sh
