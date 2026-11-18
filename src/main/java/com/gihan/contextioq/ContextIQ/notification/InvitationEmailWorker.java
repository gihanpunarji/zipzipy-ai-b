package com.gihan.contextioq.ContextIQ.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable outbox: commit the invitation first, then deliver outside a database transaction. */
@Component
@EnableScheduling
public class InvitationEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(InvitationEmailWorker.class);
    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final boolean dispatchEnabled;
    private final String from;
    private final String frontendUrl;

    public InvitationEmailWorker(JdbcTemplate jdbc, JavaMailSender sender, TransactionTemplate transactions,
        @Value("${contextiq.mail.enabled:false}") boolean enabled,
        @Value("${contextiq.mail.dispatch-enabled:true}") boolean dispatchEnabled,
        @Value("${contextiq.mail.from:}") String from,
        @Value("${contextiq.frontend-url}") String frontendUrl) {
        if (enabled && from.isBlank()) throw new IllegalArgumentException("MAIL_FROM is required when MAIL_ENABLED=true");
        this.jdbc = jdbc; this.sender = sender; this.transactions = transactions;
        this.enabled = enabled; this.dispatchEnabled = dispatchEnabled; this.from = from;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelayString = "${contextiq.mail.poll-interval-ms:30000}", initialDelayString = "${contextiq.mail.poll-interval-ms:30000}")
    public void scheduledDelivery() { if (dispatchEnabled) dispatchPending(); }

    public void dispatchPending() {
        if (!enabled) return;
        jdbc.update("""
            UPDATE organization_invitations SET email_status='CANCELLED'
            WHERE email_status IN ('QUEUED','FAILED','PROCESSING')
              AND (status<>'PENDING' OR expires_at<=CURRENT_TIMESTAMP OR cancelled_at IS NOT NULL)
            """);
        var ids = jdbc.queryForList("""
            SELECT id FROM organization_invitations WHERE status='PENDING' AND cancelled_at IS NULL AND expires_at>CURRENT_TIMESTAMP
              AND ((email_status IN ('QUEUED','FAILED') AND email_attempts<3 AND email_next_attempt<=CURRENT_TIMESTAMP)
                OR (email_status='PROCESSING' AND email_lease_until<CURRENT_TIMESTAMP))
            ORDER BY email_next_attempt LIMIT 25
            """, UUID.class);
        for (UUID id : ids) deliver(id);
    }

    private void deliver(UUID id) {
        UUID token = UUID.randomUUID();
        SimpleMailMessage message = transactions.execute(tx -> {
            int claimed = jdbc.update("""
                UPDATE organization_invitations SET email_status='PROCESSING',email_lease_token=?,email_lease_until=?,email_attempts=email_attempts+1
                WHERE id=? AND status='PENDING' AND cancelled_at IS NULL AND expires_at>CURRENT_TIMESTAMP
                  AND ((email_status IN ('QUEUED','FAILED') AND email_attempts<3 AND email_next_attempt<=CURRENT_TIMESTAMP)
                    OR (email_status='PROCESSING' AND email_lease_until<CURRENT_TIMESTAMP AND email_attempts<3))
                """, token, Timestamp.from(Instant.now().plusSeconds(120)), id);
            if (claimed == 0) {
                jdbc.update("UPDATE organization_invitations SET email_status='FAILED' WHERE id=? AND email_status='PROCESSING' AND email_lease_until<CURRENT_TIMESTAMP AND email_attempts>=3", id);
                return null;
            }
            return jdbc.queryForObject("""
                SELECT o.name, u.email, inviter.first_name, inviter.last_name, i.expires_at
                FROM organization_invitations i JOIN organizations o ON o.id=i.organization_id
                JOIN app_users u ON u.id=i.recipient_id JOIN app_users inviter ON inviter.id=i.invited_by WHERE i.id=?
                """, (rs, row) -> {
                var mail = new SimpleMailMessage();
                mail.setFrom(from); mail.setTo(rs.getString("email"));
                mail.setSubject("Organization invitation · Zipzipy AI");
                mail.setText(rs.getString("first_name") + " " + rs.getString("last_name") + " invited you to join " + rs.getString("name")
                    + ".\n\nSign in with this email address and open Notifications to accept or decline:\n"
                    + frontendUrl + "/workspaces?notifications=open\n\nExpires: " + rs.getTimestamp("expires_at").toInstant()
                    + "\nNo membership is granted until you accept. Workspace access is assigned separately.\n"
                    + "If you do not recognize this invitation, you can decline it in the app.");
                return mail;
            }, id);
        });
        if (message == null) return;
        try {
            sender.send(message);
            jdbc.update("UPDATE organization_invitations SET email_status='SENT',email_lease_until=NULL,email_lease_token=NULL WHERE id=? AND email_lease_token=?", id, token);
            log.info("Invitation email submitted to SMTP: invitation={}", id);
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE organization_invitations SET email_status='FAILED',email_next_attempt=?,email_lease_until=NULL,email_lease_token=NULL WHERE id=? AND email_lease_token=?",
                Timestamp.from(Instant.now().plusSeconds(300)), id, token);
            // SMTP exception messages may contain credentials or addresses; keep logs free of those details.
            log.warn("Invitation email delivery failed: invitation={}, failureType={}", id, failure.getClass().getSimpleName());
        }
    }
}
