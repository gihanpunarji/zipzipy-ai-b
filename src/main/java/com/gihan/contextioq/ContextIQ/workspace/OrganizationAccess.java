package com.gihan.contextioq.ContextIQ.workspace;

import com.gihan.contextioq.ContextIQ.auth.service.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Organization writers lock this row before checking roles or changing membership. */
@Service
public class OrganizationAccess {
    private final JdbcTemplate jdbc;
    private final CurrentUser current;

    public OrganizationAccess(JdbcTemplate jdbc, CurrentUser current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    public void requireAdmin(UUID organization) {
        var roles = jdbc.queryForList("SELECT role FROM organization_members WHERE organization_id=? AND user_id=? AND active=TRUE",
            String.class, organization, current.get().getId());
        if (roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found");
        if (!roles.getFirst().equals("ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization admin access is required");
    }

    public void lock(UUID organization) {
        if (jdbc.queryForList("SELECT id FROM organizations WHERE id=? FOR UPDATE", UUID.class, organization).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found");
    }

    public void lockAdmin(UUID organization) {
        lock(organization);
        // Recheck after acquiring the lock: the caller may have lost admin access while waiting.
        requireAdmin(organization);
    }
}
