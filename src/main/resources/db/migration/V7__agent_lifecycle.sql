-- Two runtime states (enabled/stopped), terminal soft-delete, credential rotation independent of state.
-- Formerly revoked Agents remain stopped and visible, but their prior token hashes are destroyed permanently.
ALTER TABLE dg_agents
 ADD COLUMN deleted_at DATETIME(3) NULL,
 ADD COLUMN token_reset_required BOOLEAN NOT NULL DEFAULT FALSE,
 ADD INDEX idx_dg_agents_visible (deleted_at,enabled);
UPDATE dg_agents
 SET enabled=0,
     token_hash=SHA2(CONCAT('retired-agent-token-',id,'-',UUID()),256),
     token_reset_required=TRUE
 WHERE revoked_at IS NOT NULL;
-- Historical revoked_at is intentionally left for safe rollback of the previous binary.
-- The current application never reads/writes it as an Agent lifecycle state.
