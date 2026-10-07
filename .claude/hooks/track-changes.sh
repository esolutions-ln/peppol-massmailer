#!/usr/bin/env bash
# PostToolUse (Edit|Write|MultiEdit): record which kinds of source changed
# this session so verify-on-stop.sh only checks what was touched.
input=$(cat)
path=$(jq -r '.tool_input.file_path // empty' <<<"$input")
session=$(jq -r '.session_id // "default"' <<<"$input")
[ -z "$path" ] && exit 0

state="${TMPDIR:-/tmp}/claude-mass-mailer-$session"
rel=${path#"$CLAUDE_PROJECT_DIR"/}

case "$rel" in
  frontend/src/*.ts|frontend/src/*.tsx)
    touch "$state.ts" ;;
  *.java)
    # Module = top-level dir with its own pom.xml (pdf-watcher-agent, ...), else root.
    module="."
    top=${rel%%/*}
    [ "$top" != "$rel" ] && [ -f "$CLAUDE_PROJECT_DIR/$top/pom.xml" ] && module=$top
    echo "$module" >>"$state.java" ;;
esac
exit 0
