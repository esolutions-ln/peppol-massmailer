#!/usr/bin/env bash
# PreToolUse (Edit|Write|MultiEdit|NotebookEdit): block edits to secrets,
# build output, vendored deps and OneDrive conflict copies.
input=$(cat)
path=$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$input")
[ -z "$path" ] && exit 0

rel=${path#"$CLAUDE_PROJECT_DIR"/}
name=$(basename "$path")

block() {
  echo "Blocked edit to '$rel': $1" >&2
  exit 2
}

case "$name" in
  .env.example) ;;
  .env|.env.*)                   block "environment file with real secrets. Edit .env.example instead." ;;
  google-oauth-credentials.json|service-account-key.json|*.key.json)
                                 block "credential file." ;;
  *"MacBook Pro"*)               block "OneDrive conflict copy. Edit the original file instead." ;;
esac

case "/$rel" in
  */node_modules/*)              block "vendored dependency. Change package.json and reinstall instead." ;;
  */frontend/dist/*)             block "Vite build output. Edit frontend/src and run 'npm run build'." ;;
  */target/*)                    block "Maven build output." ;;
esac

exit 0
