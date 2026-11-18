# Organization member management and the workspace console

## Navigation

The account sidebar includes **Workspaces** and **Organizations**. Opening a workspace card or the workspace label enters a dedicated console at `/workspaces/:id`. This console replaces the account navigation with its own sidebar: Overview, Knowledge, Workspace assistant, Members & access, and Workspace settings. The former tab bar is removed.

The top-bar workspace selector groups accessible workspaces by organization. **All workspaces** returns to the directory. The breadcrumbs link back to the organization filter and workspace. Existing deep links such as `/workspaces/:id/knowledge` and `/workspaces/:id/assistant` still work on reload. Organization admins also see an **Organization members** shortcut. Personal assistant remains a separate route, using the user's Personal workspace.

On mobile, the workspace sidebar opens as a drawer with Escape-to-close and keyboard focus containment. Opening a workspace section closes the drawer. No new browser window is required.

## Manage people

Open **Organizations → Manage members**, or **Organization members** from a workspace console. Organization admins can:

- Search active members by name/email and filter by Admin or Member.
- View their number of accessible workspaces in that organization.
- Invite registered accounts, review invitation status, cancel pending invitations and retry failed/disabled email after configuring SMTP.
- Promote accepted members to organization Admin or demote Admins to Member.
- Remove a member from the organization and revoke all their workspace memberships in one transaction.

Invitations still require recipient acceptance; changing a role cannot activate a pending/inactive account. Organization Admin permits people management and workspace creation. It does **not** grant access to all workspace documents or conversations. Workspace roles are separate and do not change when an organization role changes.

Role changes and removal have confirmation dialogs. The backend, rather than the disabled UI controls, enforces the following rules:

1. An organization retains at least one active Admin, including under concurrent requests.
2. A Personal organization's owner retains Admin access and cannot be removed.
3. A member cannot be removed if that would leave a non-deleted workspace without an active Admin. Assign a replacement workspace Admin first. The entire removal is rejected; no partial revocation occurs.
4. Removal deactivates organization membership and **all** workspace grants in that organization. Access versions change, so in-progress chat generation cannot save or return an answer after revocation. Existing documents, creator attribution and private conversation records are retained. The removed user can still use their own Personal workspace and other organizations.
5. Rejoining requires a new invitation and acceptance, followed by new workspace role assignments. Old workspace permissions are not restored.
6. Demoting/removing an organization Admin cancels their unanswered invitations. Removal also cancels pending invitations addressed to that member. A replacement admin can issue a new invitation without waiting for the cancelled invitation to expire.

Accepted invitation notifications record a historical response; they are not proof of current membership. The member roster and backend authorization use current active memberships.

## Cancellation and concurrency

Flyway **V10** adds nullable `cancelled_at` to invitations; no existing membership or history is removed. Cancelled invitations are presented with status `CANCELLED`. They cannot be accepted or requeued for email. Cancelling twice is idempotent; cancelling an already accepted/rejected/expired invitation returns 409. Email not yet sent is cancelled. An email already in flight may still arrive, but its invitation cannot be accepted.

Organization writers acquire the organization row lock before rechecking admin access. Creation, acceptance, cancellation and role changes serialize on that row. Removal then locks workspaces in ID order, sharing the locks used by workspace membership writes and generation finalization. Model calls never run while these locks are held. This also prevents two admins from concurrently demoting each other and leaving no admin.

## Apply the update

The running services are not restarted by this implementation. Rebuild the backend using your existing workflow to apply V10 and activate the role/removal/cancellation endpoints. With the Compose setup, run manually from the backend folder:

```sh
docker compose up -d --build --no-deps backend
```

Keep the existing PostgreSQL volume. No re-embedding or credential change is required. Refresh the frontend. If it detects an older member response without `workspaceCount`, it shows existing members but disables the new management controls with an update-required explanation.

## Verify

```sh
./mvnw test
cd ../ContextIQ-frontend
npm run build
```

Tests cover roles and authorization, validation/CSRF, PATCH CORS preflight, last-Admin and Personal-owner protection, atomic rejection of orphaning removal, cross-organization requests, full workspace revocation, revocation during answer generation, concurrent role changes, invitation cancellation and re-invitation. Persistence tests use H2 in PostgreSQL compatibility mode; applying V10 to the running PostgreSQL container is a manual deployment step.

Manual checks after deployment: invite and accept a second account; promote/demote it; verify organization Admin alone cannot read a workspace without a workspace grant. Assign a workspace role, remove the organization member, and verify their document/history requests fail. Ensure the last Admin and the only workspace Admin cannot be removed. Finally, open a workspace from the directory, navigate with its sidebar, switch workspaces and reload a section's URL.
