@AGENTS.md

## Claude Code notes

- `AGENTS.md` (imported above) is the source of truth — update it, not this file,
  when architecture, mail transport, build or deploy steps change.
- Run Maven with JDK 25: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -o test`
  from `massmailer/` (the machine default JDK is newer and won't compile this project).
- Never read or print `massmailer/.env` or real Brevo keys; use `.env.example`.
- Production lives on a remote server; deploying requires running `deploy.sh`
  there — don't attempt it without the user's explicit go-ahead.
