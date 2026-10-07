---
name: smoke
description: Smoke-test the running InvoiceDirect / mass-mailer stack (backend :9199, frontend :8199) - health, PEPPOL AS4, OpenAPI, API-key auth, and the nginx proxy. Use after starting the app, after a docker compose rebuild, or to confirm a backend/security/proxy change works end to end.
---

# Smoke test

Run the bundled script from the repo root:

```bash
bash .claude/skills/smoke/smoke.sh
```

- Read-only by default. It expects `401`/`403` on `/api/v1/organizations` without a valid `X-API-Key`; a `200` there means auth is broken.
- Pass `--peppol` to also POST a sample UBL invoice to `/peppol/as4/receive`. This **writes a row to the inbox**, so only use it on a local/dev stack, never against production.
- Override targets with `BASE=http://127.0.0.1:9199 FRONT=http://127.0.0.1:8199`. Use `FRONT=http://127.0.0.1:3000` when running `npm run dev` instead of the Docker frontend.

If nothing answers, the stack isn't up. Start it with `docker compose up -d` (Postgres + backend + frontend) or the H2 dev command in `CLAUDE.md`, then wait for `/actuator/health` before re-running.

Report each failed line with its status code, then investigate the backend log (`docker compose logs --tail=100 <service>`) before changing code.
