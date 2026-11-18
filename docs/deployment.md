# Portfolio deployment: one Ubuntu VM

Status: deployment files prepared; no cloud VM provisioned and no public deployment verified yet.

Local verification: frontend TypeScript/production build and Docker image build passed; Caddy validation and Compose validation passed; shell syntax and private-config generation checks passed. Overview response checks reject legacy/malformed/wrong-workspace payloads. `npm audit --omit=dev` reported zero production dependency advisories. The container install reported four high-severity development dependency advisories; these build tools are not copied to the Caddy runtime image. No live TLS issuance, server model inference, or end-to-end cloud test is possible until a VM and hostname exist.

## Architecture

Caddy serves the compiled React app and proxies `/api/*`, `/oauth2/*`, and `/login/oauth2/*` to Spring Boot on the same HTTPS origin. SPA routes fall back to index.html; API failures never fall back to HTML. Only Caddy exposes ports. PostgreSQL/pgvector and Ollama stay on the Compose network. Four named volumes retain database data, models, and TLS state. The production Compose project is separate from development; it does not reuse your local data.

The frontend uses `/api/v1`; OAuth resolves that relative address against the browser origin. Spring handles trusted proxy headers behind Caddy and issues Secure, HttpOnly, SameSite=Lax session cookies. Session login is lost when the backend restarts; conversations and documents remain persisted. The deployment does not need an LLM API key.

## 1. Create the free server and DNS

Create an Always Free eligible Ubuntu Ampere A1 VM in your Oracle home region. As checked on 2026-11-18, Oracle documents 2 OCPUs / 12 GB RAM for Always Free tenancies and 200 GB combined boot/block storage. Choose a 50–100 GB boot disk within your account's total allowance. Check the console's cost estimate and labels before provisioning; do not substitute paid shapes. Capacity is not guaranteed and idle VMs can be reclaimed. See https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm.

Point a hostname you control (a free DuckDNS hostname works) at the public IP. Allow inbound TCP 80/443 and SSH 22 from your IP in Oracle network rules and the host firewall. Do not expose 5432, 8081, or 11434. Docker-published ports can bypass UFW; keep the Oracle network rules narrow. No extra public ports or cloud load balancers are needed.

## 2. Transfer both checkouts

Keep this layout on the VM, including the uncommitted deployment files if they have not been pushed:

```
~/contextiq/ContextIQ/
~/contextiq/ContextIQ-frontend/
```

Use Git after committing the intended changes, or copy source files over SSH, excluding `.env*`, `.git`, `target`, `node_modules`, `dist`, and `backups`. Do not copy your local database, production secrets, or SSH key into either checkout. Container builds install dependencies; Node and Java need not be installed on the host.

To bundle the current files on your Mac without committing unrelated work:

```bash
bash deploy/package.sh
```

The command prints a private temporary archive path. Copy that archive to the VM using `scp -i YOUR_KEY ARCHIVE ubuntu@SERVER_IP:~/`, then extract it into `~/contextiq`. Inspect the archive before transfer if you have added other sensitive files under either repository. Do not put an SSH private key inside the project.

## 3. Install and configure

On the new VM, from `~/contextiq/ContextIQ`:

```bash
bash deploy/install-docker-ubuntu.sh
python3 deploy/configure.py --host YOUR_HOSTNAME --email YOUR_CERTIFICATE_EMAIL
```

The generator validates the supplied hostname, writes `.env.production` with permission 0600, and generates a random database password without printing it. It refuses to overwrite an existing file. Use a bare DNS hostname, not a URL. `.env.production` is Git-ignored. Do not run `docker compose config` without `--quiet` when sharing logs because the full output includes secrets.

## 4. Deploy

```bash
sudo bash deploy/deploy.sh
```

This builds the current backend (including its tests) and frontend, starts PostgreSQL and Ollama, downloads both configured models, then starts Spring Boot and Caddy. Caddy obtains HTTPS certificates automatically when DNS and public ports are ready. The script polls the public health URL and exits with an error if it does not respond. It never deletes volumes.

Models default to `gemma4:e2b` and `nomic-embed-text:latest`. CPU inference is slower than a GPU or Apple Silicon; benchmark on the actual VM. Defaults limit the model context to 8192 and output to 800 tokens, with one concurrent Ollama request. Memory budgets are 7 GB Ollama, 2 GB backend, 1 GB PostgreSQL, leaving space for the OS and Caddy on a 12 GB VM. Large documents or simultaneous requests may still exhaust resources. Changing the embedding model requires reindexing; the current vector schema expects 768 dimensions. Image tags should be pinned to tested digests before relying on reproducible long-term deployments.

## 5. Optional Google login and SMTP

Edit `.env.production` on the VM; enable Google login only after adding your provider credentials. Register `https://YOUR_HOSTNAME/login/oauth2/code/google` as the redirect URI. Google's OAuth consent configuration may restrict test users. Keep credentials server-side.

For email, set MAIL_ENABLED=true and fill MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM. STARTTLS with SMTP authentication defaults to enabled. SMTP provider approval, quotas, and network availability must be tested separately. Invitations remain available in-app with email disabled. Values with Compose interpolation characters must be quoted/escaped appropriately; use single quotes for literal secrets in the env file.

Redeploy after changing configuration. Never regenerate DB_PASSWORD on an existing database volume: changing POSTGRES_PASSWORD does not update an existing PostgreSQL role password.

## 6. Acceptance checks before sharing the URL

- HTTPS health returns 200 and `/dashboard` reloads successfully.
- Register, log in, refresh, and log out. Confirm the session cookie is Secure/HttpOnly.
- Overview shows LIVE counts (or a clear incompatible-backend error, never a render crash).
- Upload a small synthetic document, ask a document-grounded question, and open its citation.
- Reload the chat and confirm history remains.
- Verify organization invitations require acceptance and inaccessible workspace data stays inaccessible.
- Test Google login and invitation delivery only if enabled.
- Restart the production backend and confirm stored documents and chats remain after signing in again.
- Test latency with the real models; don't treat a successful health response as proof that generation works.

The demo has no deployment-level abuse/rate limiting added by these files. Public registration and document parsing consume real CPU/storage. Use synthetic data, monitor usage, and limit sharing until request/upload quotas are implemented. This is a portfolio deployment, not production hardening.

## Logs, updates, backups, recovery

```bash
sudo docker compose --env-file .env.production -f compose.production.yml ps
sudo docker compose --env-file .env.production -f compose.production.yml logs --tail=100 backend web
sudo bash deploy/backup.sh
```

Copy each backup off the VM. Backups contain private data; restrict access. Back up before updating source and running deploy.sh again. A backend rollback may require restoring a compatible database backup if migrations changed; never run `down -v` as an update step.

Restore into a NEW empty deployment database with the backend stopped, using `pg_restore -U contextiq -d contextiq --no-owner --no-acl` inside the postgres service, feeding the dump on stdin (`exec -T`). Do not restore over live data. Validate the restore before replacing a working deployment. Caddy can reissue certificates and models can be downloaded again; the PostgreSQL dump is the essential application backup.

Official references: https://docs.docker.com/engine/install/ubuntu/ and https://caddyserver.com/docs/caddyfile/patterns.
