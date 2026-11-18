package com.gihan.contextioq.ContextIQ.notification;

import com.gihan.contextioq.ContextIQ.auth.service.CurrentUser;
import com.gihan.contextioq.ContextIQ.workspace.OrganizationAccess;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvitationService {
    public enum Decision {ACCEPT, REJECT}

    public record DecisionRequest(@jakarta.validation.constraints.NotNull Decision decision) {
    }

    public record InvitationView(UUID id, UUID organizationId, String organizationName, String recipientEmail,
                                 String invitedByName, String status, Instant createdAt, Instant expiresAt,
                                 Instant readAt, String emailStatus) {
    }

    public record NotificationPage(List<InvitationView> items, long unreadCount, boolean hasMore) {
    }

    private final OrganizationAccess organizationAccess;
    private final JdbcTemplate jdbc;
    private final CurrentUser current;
    private final boolean mailEnabled;
    private final int ttlDays;

    public InvitationService(JdbcTemplate jdbc, CurrentUser current, OrganizationAccess organizationAccess, @Value("${contextiq.mail.enabled:false}") boolean mailEnabled,
                             @Value("${contextiq.invitations.ttl-days:7}") int ttlDays) {
        if (ttlDays < 1 || ttlDays > 30) throw new IllegalArgumentException("Invitation lifetime must be 1–30 days");
        this.organizationAccess = organizationAccess;
        this.jdbc = jdbc;
        this.current = current;
        this.mailEnabled = mailEnabled;
        this.ttlDays = ttlDays;
    }

    private static final String SELECT = """
            SELECT i.*, o.name AS organization_name, recipient.email AS recipient_email,
             inviter.first_name, inviter.last_name
            FROM organization_invitations i JOIN organizations o ON o.id=i.organization_id
            JOIN app_users recipient ON recipient.id=i.recipient_id JOIN app_users inviter ON inviter.id=i.invited_by
            """;
    private final RowMapper<InvitationView> mapper = (rs, row) -> {
        Instant expires = rs.getTimestamp("expires_at").toInstant();
        String status = rs.getString("status");
        if (rs.getTimestamp("cancelled_at") != null) status = "CANCELLED";
        if (status.equals("PENDING") && !expires.isAfter(Instant.now())) status = "EXPIRED";
        var read = rs.getTimestamp("read_at");
        return new InvitationView(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getString("organization_name"),
                rs.getString("recipient_email"), (rs.getString("first_name") + " " + rs.getString("last_name")).strip(), status,
                rs.getTimestamp("created_at").toInstant(), expires, read == null ? null : read.toInstant(), rs.getString("email_status"));
    };

    @Transactional
    public InvitationView invite(UUID organization, String email) {
        organizationAccess.lockAdmin(organization);
        UUID recipient = jdbc.queryForList("SELECT id FROM app_users WHERE LOWER(email)=?", UUID.class, email.strip().toLowerCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Registered account not found"));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_members WHERE organization_id=? AND user_id=? AND active=TRUE", Integer.class, organization, recipient) > 0)
            throw error(HttpStatus.CONFLICT, "This account is already an organization member");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_invitations WHERE organization_id=? AND recipient_id=? AND status='PENDING' AND cancelled_at IS NULL AND expires_at>CURRENT_TIMESTAMP", Integer.class, organization, recipient) > 0)
            throw error(HttpStatus.CONFLICT, "An invitation is already pending for this account");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO organization_invitations(id,organization_id,recipient_id,invited_by,status,created_at,expires_at,email_status,email_next_attempt)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """, id, organization, recipient, current.get().getId(), Timestamp.from(now), Timestamp.from(now.plus(ttlDays, ChronoUnit.DAYS)), mailEnabled ? "QUEUED" : "DISABLED", Timestamp.from(now));
        return byId(id);
    }

    public NotificationPage notifications(int offset) {
        UUID user = current.get().getId();
        var items = jdbc.query(SELECT + " WHERE i.recipient_id=? ORDER BY i.created_at DESC,i.id LIMIT 21 OFFSET ?", mapper, user, offset);
        long unread = jdbc.queryForObject("SELECT COUNT(*) FROM organization_invitations WHERE recipient_id=? AND read_at IS NULL", Long.class, user);
        return new NotificationPage(items.stream().limit(20).toList(), unread, items.size() > 20);
    }

    public List<InvitationView> sent(UUID organization) {
        organizationAccess.requireAdmin(organization);
        return jdbc.query(SELECT + " WHERE i.organization_id=? ORDER BY i.created_at DESC,i.id LIMIT 100", mapper, organization);
    }

    @Transactional
    public void readAll() {
        jdbc.update("UPDATE organization_invitations SET read_at=CURRENT_TIMESTAMP WHERE recipient_id=? AND read_at IS NULL", current.get().getId());
    }

    @Transactional
    public InvitationView respond(UUID id, Decision decision) {
        UUID user = current.get().getId();
        var orgs = jdbc.queryForList("SELECT organization_id FROM organization_invitations WHERE id=? AND recipient_id=?", UUID.class, id, user);
        if (orgs.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Invitation not found");
        UUID organization = orgs.getFirst();
        organizationAccess.lock(organization);
        var invitation = byId(id);
        String resolved = decision == Decision.ACCEPT ? "ACCEPTED" : "REJECTED";
        if (invitation.status().equals(resolved)) return invitation; // A repeated click cannot duplicate membership.
        if (invitation.status().equals("EXPIRED"))
            throw error(HttpStatus.GONE, "This invitation has expired. Ask an administrator for a new one.");
        if (!invitation.status().equals("PENDING"))
            throw error(HttpStatus.CONFLICT, "This invitation is no longer pending");
        if (decision == Decision.ACCEPT) {
            int validInviter = jdbc.queryForObject("SELECT COUNT(*) FROM organization_invitations i JOIN organization_members m ON m.organization_id=i.organization_id AND m.user_id=i.invited_by WHERE i.id=? AND m.active=TRUE AND m.role='ADMIN'", Integer.class, id);
            if (validInviter == 0)
                throw error(HttpStatus.CONFLICT, "The inviter can no longer authorize membership. Ask an administrator for a new invitation.");
            var active = jdbc.queryForList("SELECT active FROM organization_members WHERE organization_id=? AND user_id=?", Boolean.class, organization, user);
            if (active.isEmpty())
                jdbc.update("INSERT INTO organization_members VALUES (?, ?, 'MEMBER', TRUE)", organization, user);
            else if (!active.getFirst()) {
                // Accepting organization membership never resurrects old workspace grants.
                var workspaces = jdbc.queryForList("SELECT id FROM workspaces WHERE organization_id=? ORDER BY id FOR UPDATE", UUID.class, organization);
                for (UUID workspace : workspaces)
                    jdbc.update("UPDATE workspace_members SET active=FALSE,access_version=? WHERE workspace_id=? AND user_id=?", UUID.randomUUID(), workspace, user);
                jdbc.update("UPDATE organization_members SET active=TRUE,role='MEMBER' WHERE organization_id=? AND user_id=?", organization, user);
            }
        }
        jdbc.update("UPDATE organization_invitations SET status=?,responded_at=CURRENT_TIMESTAMP,read_at=CURRENT_TIMESTAMP WHERE id=?", resolved, id);
        return byId(id);
    }

    @Transactional
    public InvitationView retryEmail(UUID organization, UUID id) {
        organizationAccess.lockAdmin(organization);
        var invitation = byId(id);
        if (!invitation.organizationId().equals(organization))
            throw error(HttpStatus.NOT_FOUND, "Invitation not found");
        if (!mailEnabled) throw error(HttpStatus.CONFLICT, "Email delivery is not configured");
        if (!invitation.status().equals("PENDING"))
            throw error(HttpStatus.CONFLICT, "Only pending invitations can be emailed");
        if (jdbc.update("UPDATE organization_invitations SET email_status='QUEUED',email_attempts=0,email_next_attempt=CURRENT_TIMESTAMP WHERE id=? AND email_status IN ('FAILED','DISABLED')", id) == 0)
            throw error(HttpStatus.CONFLICT, "This email was already sent or is queued for delivery");
        return byId(id);
    }

    @Transactional
    public InvitationView cancel(UUID organization, UUID id) {
        organizationAccess.lockAdmin(organization);
        var invitation = byId(id);
        if (!invitation.organizationId().equals(organization)) throw error(HttpStatus.NOT_FOUND, "Invitation not found");
        if (invitation.status().equals("CANCELLED")) return invitation;
        if (!invitation.status().equals("PENDING")) throw error(HttpStatus.CONFLICT, "Only pending invitations can be cancelled");
        jdbc.update("""
            UPDATE organization_invitations SET cancelled_at=CURRENT_TIMESTAMP,
              email_status=CASE WHEN email_status='SENT' THEN 'SENT' ELSE 'CANCELLED' END,
              email_lease_token=NULL,email_lease_until=NULL WHERE id=?
            """, id);
        return byId(id);
    }

    private InvitationView byId(UUID id) {
        return jdbc.query(SELECT + " WHERE i.id=?", mapper, id).stream().findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Invitation not found"));
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
