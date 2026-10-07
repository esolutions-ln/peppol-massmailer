#!/usr/bin/env bash
# Stop: if Java or frontend TypeScript was edited this session, compile it
# before Claude finishes. On failure, exit 2 so Claude sees the errors and
# keeps working. Markers are cleared only on success.
input=$(cat)
session=$(jq -r '.session_id // "default"' <<<"$input")
active=$(jq -r '.stop_hook_active // false' <<<"$input")
state="${TMPDIR:-/tmp}/claude-mass-mailer-$session"

# Already blocked once this turn: let Claude stop rather than loop forever.
[ "$active" = "true" ] && exit 0

cd "$CLAUDE_PROJECT_DIR" || exit 0
failed=0

if [ -f "$state.java" ]; then
  for module in $(sort -u "$state.java"); do
    out=$(cd "$module" && mvn -q -o test-compile -DskipTests 2>&1)
    rc=$?
    # Offline mode fails if a dependency isn't cached yet; retry online only then.
    if [ $rc -ne 0 ] && grep -qE 'offline mode|Could not resolve' <<<"$out"; then
      out=$(cd "$module" && mvn -q test-compile -DskipTests 2>&1)
      rc=$?
    fi
    if [ $rc -ne 0 ]; then
      echo "Java compile failed in module '$module' (mvn -q test-compile):" >&2
      grep -E '\[ERROR\]' <<<"$out" | head -30 >&2
      failed=1
    fi
  done
  [ $failed -eq 0 ] && rm -f "$state.java"
fi

if [ -f "$state.ts" ]; then
  # Call tsc directly: OneDrive breaks the node_modules/.bin symlinks.
  out=$(cd frontend && node node_modules/typescript/bin/tsc --noEmit -p . 2>&1)
  rc=$?
  errors=$(grep -E 'error TS[0-9]+' <<<"$out")
  if [ $rc -ne 0 ] && ! grep -qvE 'error TS(6053|2688)' <<<"$errors"; then
    # Only "file/type library not found" errors: a broken node_modules
    # install (OneDrive offloading), not a code problem. Warn, don't block.
    rm -f "$state.ts"
    jq -n '{systemMessage: "Frontend typecheck skipped: frontend/node_modules is incomplete (TS6053/TS2688). Run: cd frontend && rm -rf node_modules && npm ci"}'
  elif [ $rc -ne 0 ]; then
    echo "Frontend typecheck failed (tsc --noEmit):" >&2
    head -30 <<<"$out" >&2
    failed=1
  else
    rm -f "$state.ts"
  fi
fi

[ $failed -eq 1 ] && exit 2
exit 0
