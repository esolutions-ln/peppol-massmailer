#!/usr/bin/env bash
# PreToolUse (Bash): force a confirmation prompt for commands that deploy,
# touch production, send real email, or publish to the remote. Overrides
# any allow rule in settings.local.json.
cmd=$(jq -r '.tool_input.command // empty' <<<"$(cat)")
[ -z "$cmd" ] && exit 0

reason=""
case "$cmd" in
  *deploy.sh*|*deploy-native.sh*)    reason="runs a deploy script" ;;
  *docker-compose.prod.yml*)         reason="touches the production compose stack" ;;
  *send-test-email.sh*)              reason="sends a real email" ;;
  *"git push"*)                      reason="pushes to the GitHub remote" ;;
  "ssh "*|*" ssh "*|*"scp "*|*"rsync "*) reason="connects to a remote host" ;;
  *api.brevo.com*)                   reason="calls the live Brevo email API" ;;
esac

[ -z "$reason" ] && exit 0

jq -n --arg r "Guard: this command $reason. Confirm before running." '{
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    permissionDecision: "ask",
    permissionDecisionReason: $r
  }
}'
