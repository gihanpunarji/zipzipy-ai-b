package com.gihan.contextioq.ContextIQ.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DashboardSummary(
    String mode, UUID workspaceId, Instant generatedAt,
    long documents, long conversations, long queries, long workspaces,
    List<Activity> recentActivity
) {
    public record Activity(UUID id, String type, String title, Instant occurredAt) {}
}
