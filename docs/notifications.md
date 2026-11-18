# Organization invitations and account notifications

An organization admin invites an existing registered account by email. This creates a **pending invitation**, not an active organization member. The recipient sees the invitation in the bell popover beside their avatar, even when they are viewing a different workspace. They choose **Accept** or **Decline**. Admins may cancel pending invitations; the recipient then sees Cancelled. Only the recipient may respond, using an authenticated, CSRF-protected POST.

Acceptance grants organization `MEMBER` membership. It does not add the user to any workspace. A workspace admin must then assign Admin, Editor or Viewer access through that workspace's Members tab. Rejoining an organization clears previously revoked workspace grants rather than restoring them. Existing active memberships are preserved by this migration; existing members are not retroactively turned into invitations.

Invitations expire after seven days by default (`INVITATION_TTL_DAYS`, allowed range 1–30). Repeating the same response is safe; switching a completed decision returns 409. Duplicate pending invitations and inviting an already-active member return 409. Expired invitations return 410 on response. The inviter must still be an active organization Admin when an invitation is accepted.

Notifications are stored in PostgreSQL by Flyway V9. They survive reloads and backend restarts. The frontend polls every 30 seconds and refreshes on focus. It displays twenty notifications per page, an account-wide unread count and a **Mark all read** action. Accepting or declining also marks that invitation read. Admins can inspect the latest 100 sent invitations and their email delivery status in Organization management.

## Configure JavaMailSender

No credentials are committed. Copy the mail entries from `.env.example` into your existing ignored backend `.env`; fill them in without replacing your database/Ollama settings:

```dotenv
MAIL_ENABLED=true
MAIL_HOST=your-smtp-host
MAIL_PORT=587
MAIL_USERNAME=your-smtp-username
MAIL_PASSWORD=your-smtp-password
MAIL_FROM=your-verified-sender-address
MAIL_SMTP_AUTH=true
MAIL_STARTTLS_ENABLED=true
MAIL_SSL_ENABLED=false
INVITATION_TTL_DAYS=7
FRONTEND_URL=http://localhost:5173
```

These values are placeholders: use your email provider's actual SMTP settings and authorized sender address. For providers requiring implicit TLS on port 465, use `MAIL_PORT=465`, `MAIL_SSL_ENABLED=true`, `MAIL_STARTTLS_ENABLED=false`. Never put SMTP credentials in `VITE_*` variables. `FRONTEND_URL` must be the origin recipients can reach; localhost works only on the same machine. It also controls the application's existing frontend/CORS setup.

Leave `MAIL_ENABLED=false` to use only in-app notifications. When enabled, a nonempty `MAIL_FROM` is required. Spring Boot creates the `JavaMailSender`; bounded connection, read and write timeouts prevent a stalled SMTP server from indefinitely blocking the worker. No external mail service is called during tests.

## Delivery behavior

The invitation row also acts as a durable outbox. A scheduled worker checks every 30 seconds and claims up to 25 due records using conditional database updates and a two-minute lease. SMTP runs after the claim transaction commits. Success means the message was **submitted to the mail server**, not that the recipient opened it or that inbox delivery is guaranteed.

Failures retry up to three attempts, five minutes apart. SMTP failure never rolls back the invitation or removes the notification. A crash releases the claim after the lease expires, subject to the same attempt limit. Delivery is at least once: a crash after SMTP accepts the email but before the database update may cause a duplicate email. Recipient acceptance itself is idempotent.

States: `DISABLED`, `QUEUED`, `PROCESSING`, `SENT`, `FAILED`, `CANCELLED`. Once SMTP is configured, an organization admin can use **Retry email** for a pending invitation created while email was disabled or whose delivery failed. Expired or answered invitations are cancelled by the worker before queued delivery; an already in-flight email can still arrive. Emails link to `/workspaces?notifications=open`, which opens the account panel after sign-in. There are no unauthenticated acceptance links or secrets in email URLs.

## Apply and verify manually

The implementation does not restart your services. From the backend directory, after filling your optional mail settings, rebuild the backend using your normal workflow:

```sh
docker compose up -d --build --no-deps backend
```

Flyway applies V9 on startup. Keep the existing database volume. Refresh the frontend; Vite will pick up source changes. The invitation form stays disabled when it cannot load the new invitation endpoints, preventing accidental use of an older backend's automatic-membership endpoint.

1. Sign in as an organization admin and open Workspaces → Organization management.
2. Invite a second registered account. Confirm it appears as Pending, not in the active member list.
3. Sign in as that second account in a separate browser profile. Open the bell next to the avatar. Accept or decline; reload to verify the saved status.
4. After acceptance, the admin assigns a workspace role. Knowledge access remains denied before that assignment.
5. With SMTP configured, verify email arrival and its sign-in link. Without SMTP, in-app acceptance still works.

Automated coverage uses MockMvc/H2 and a mocked JavaMailSender: pending membership, recipient-only visibility, CSRF, validation, acceptance/rejection/idempotency, expiry, duplicate invites, inviter authorization, safe rejoining, delivery, skipped delivery after a response, and bounded SMTP retries. Live SMTP delivery and the running PostgreSQL container require the manual deployment/configuration above.

## Current boundaries

Recipients must already have a registered account. Inviting unregistered addresses, notification deletion/preferences and bounce tracking are not implemented. V10 adds admin cancellation and organization role management/removal; see [organization management](organization-management.md). Existing organization and workspace permission checks remain authoritative in the backend, including during RAG retrieval.
