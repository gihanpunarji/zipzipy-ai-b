-- Retain invitation history while allowing administrators to withdraw pending invitations.
ALTER TABLE organization_invitations ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
