package com.gihan.contextioq.ContextIQ.workspace;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrganizationMemberService {
    public enum Role { ADMIN, MEMBER }
    public record RoleRequest(@NotNull Role role) {}
    public record MemberView(UUID userId, String firstName, String lastName, String email, Role role, long workspaceCount) {}
    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;

    public OrganizationMemberService(JdbcTemplate jdbc, OrganizationAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public List<MemberView> list(UUID organization) {
        access.requireAdmin(organization);
        return jdbc.query("""
            SELECT u.id,u.first_name,u.last_name,u.email,m.role,
              (SELECT COUNT(*) FROM workspace_members wm JOIN workspaces w ON w.id=wm.workspace_id
               WHERE wm.organization_id=m.organization_id AND wm.user_id=m.user_id AND wm.active=TRUE AND w.deleted=FALSE) AS workspace_count
            FROM organization_members m JOIN app_users u ON u.id=m.user_id
            WHERE m.organization_id=? AND m.active=TRUE ORDER BY u.email,u.id
            """, (rs,row) -> new MemberView(rs.getObject("id",UUID.class),rs.getString("first_name"),rs.getString("last_name"),
                rs.getString("email"),Role.valueOf(rs.getString("role")),rs.getLong("workspace_count")),organization);
    }

    @Transactional
    public void changeRole(UUID organization, UUID user, Role role) {
        access.lockAdmin(organization);
        Role before = activeRole(organization,user);
        if (before == role) return;
        if (role != Role.ADMIN) protectAdmin(organization,user,before);
        jdbc.update("UPDATE organization_members SET role=? WHERE organization_id=? AND user_id=?",role.name(),organization,user);
        if (role != Role.ADMIN) cancelInvitations(organization,user,false);
        // Organization role changes intentionally do not grant/revoke separate workspace roles.
    }

    @Transactional
    public void remove(UUID organization, UUID user) {
        access.lockAdmin(organization);
        protectAdmin(organization,user,activeRole(organization,user));
        // Use the same workspace locks as retrieval finalization and workspace membership writers.
        // Always lock organization -> sorted workspaces, never the reverse.
        jdbc.queryForList("SELECT id FROM workspaces WHERE organization_id=? ORDER BY id FOR UPDATE",UUID.class,organization);
        int orphaned = jdbc.queryForObject("""
            SELECT COUNT(*) FROM workspace_members target JOIN workspaces w ON w.id=target.workspace_id
            WHERE target.organization_id=? AND target.user_id=? AND target.active=TRUE AND target.role='ADMIN' AND w.deleted=FALSE
              AND NOT EXISTS (SELECT 1 FROM workspace_members other
                JOIN organization_members om ON om.organization_id=other.organization_id AND om.user_id=other.user_id
                WHERE other.workspace_id=target.workspace_id AND other.user_id<>target.user_id
                  AND other.active=TRUE AND other.role='ADMIN' AND om.active=TRUE)
            """,Integer.class,organization,user);
        if (orphaned > 0) throw conflict("This member is the only admin of " + orphaned + " workspace(s). Assign another workspace admin before removing them.");
        jdbc.update("UPDATE workspace_members SET active=FALSE,access_version=? WHERE organization_id=? AND user_id=?",UUID.randomUUID(),organization,user);
        jdbc.update("UPDATE organization_members SET active=FALSE WHERE organization_id=? AND user_id=?",organization,user);
        cancelInvitations(organization,user,true);
    }

    private Role activeRole(UUID organization, UUID user) {
        return jdbc.queryForList("SELECT role FROM organization_members WHERE organization_id=? AND user_id=? AND active=TRUE",String.class,organization,user)
            .stream().map(Role::valueOf).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization member not found"));
    }

    private void protectAdmin(UUID organization, UUID user, Role before) {
        if (organization.equals(user) && jdbc.queryForObject("SELECT COUNT(*) FROM workspaces WHERE id=? AND personal=TRUE",Integer.class,organization)>0)
            throw conflict("The personal organization owner must retain admin access");
        int admins = jdbc.queryForObject("SELECT COUNT(*) FROM organization_members WHERE organization_id=? AND active=TRUE AND role='ADMIN'",Integer.class,organization);
        if (before == Role.ADMIN && admins <= 1) throw conflict("An organization must retain at least one admin");
    }

    private void cancelInvitations(UUID organization, UUID user, boolean includeRecipient) {
        jdbc.update("""
            UPDATE organization_invitations SET cancelled_at=CURRENT_TIMESTAMP,
              email_status=CASE WHEN email_status='SENT' THEN 'SENT' ELSE 'CANCELLED' END,
              email_lease_token=NULL,email_lease_until=NULL
            WHERE organization_id=? AND status='PENDING' AND cancelled_at IS NULL
              AND (invited_by=? OR (?=TRUE AND recipient_id=?))
            """,organization,user,includeRecipient,user);
    }

    private ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT,detail); }
}
