package com.gihan.contextioq.ContextIQ.dashboard.service;

import com.gihan.contextioq.ContextIQ.dashboard.dto.DashboardSummary;
import com.gihan.contextioq.ContextIQ.dashboard.dto.DashboardSummary.Activity;
import com.gihan.contextioq.ContextIQ.workspace.WorkspaceAccess;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccess access;

    public DashboardService(JdbcTemplate jdbc, WorkspaceAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Transactional
    public DashboardSummary summary() {
        // Membership changes take the same lock, keeping authorization valid throughout this read.
        var scope = access.lock(access.current());
        var workspace = scope.workspaceId();
        var user = scope.userId();
        long documents = count("SELECT COUNT(*) FROM documents WHERE workspace_id=?", workspace);
        long conversations = count("SELECT COUNT(*) FROM conversations WHERE workspace_id=? AND owner_id=?", workspace, user);
        long queries = count("""
            SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id=m.conversation_id
            WHERE c.workspace_id=? AND c.owner_id=? AND m.role='USER'
            """, workspace, user);
        long workspaces = count("""
            SELECT COUNT(*) FROM workspaces w
            JOIN workspace_members wm ON wm.workspace_id=w.id AND wm.organization_id=w.organization_id
            JOIN organization_members om ON om.organization_id=w.organization_id AND om.user_id=wm.user_id
            WHERE wm.user_id=? AND wm.active=TRUE AND om.active=TRUE AND w.deleted=FALSE
            """, user);
        // Documents are shared; conversation titles remain private to their creator.
        var activity = jdbc.query("""
            SELECT id, activity_type, title, occurred_at FROM (
                SELECT id, 'DOCUMENT' AS activity_type, filename AS title, created_at AS occurred_at
                FROM documents WHERE workspace_id=?
                UNION ALL
                SELECT id, 'CONVERSATION' AS activity_type, title, updated_at AS occurred_at
                FROM conversations WHERE workspace_id=? AND owner_id=?
            ) activity ORDER BY occurred_at DESC, id LIMIT 8
            """, (rs, row) -> new Activity(rs.getObject("id", UUID.class), rs.getString("activity_type"),
                rs.getString("title"), rs.getTimestamp("occurred_at").toInstant()), workspace, workspace, user);
        return new DashboardSummary("LIVE", workspace, Instant.now(), documents, conversations, queries, workspaces, activity);
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }
}
