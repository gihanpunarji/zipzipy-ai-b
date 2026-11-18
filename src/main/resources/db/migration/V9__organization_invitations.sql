CREATE TABLE organization_invitations (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL REFERENCES organizations(id),
 recipient_id UUID NOT NULL REFERENCES app_users(id),
 invited_by UUID NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','ACCEPTED','REJECTED')),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 responded_at TIMESTAMP WITH TIME ZONE,
 read_at TIMESTAMP WITH TIME ZONE,
 email_status VARCHAR(16) NOT NULL CHECK(email_status IN ('QUEUED','PROCESSING','SENT','FAILED','DISABLED','CANCELLED')),
 email_attempts INTEGER NOT NULL DEFAULT 0,
 email_next_attempt TIMESTAMP WITH TIME ZONE NOT NULL,
 email_lease_until TIMESTAMP WITH TIME ZONE,
 email_lease_token UUID,
 FOREIGN KEY(invited_by,organization_id) REFERENCES organization_members(user_id,organization_id)
);
CREATE INDEX idx_invitations_recipient ON organization_invitations(recipient_id,created_at DESC,id);
CREATE INDEX idx_invitations_organization_recipient ON organization_invitations(organization_id,recipient_id,status);
CREATE INDEX idx_invitations_email ON organization_invitations(email_status,email_next_attempt);
