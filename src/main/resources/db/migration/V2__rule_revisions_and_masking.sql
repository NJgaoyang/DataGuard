-- Security policy revisions and explicit output-field masking.
-- Revoking platform source/field permissions also deletes delegated Agent grants.
ALTER TABLE dg_source_columns ADD COLUMN masked BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'true means return [REDACTED] instead of a field value';
ALTER TABLE dg_rules ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT 'Increment on every policy mutation; old approvals cannot be reused';
ALTER TABLE dg_requests ADD COLUMN rule_revision INT NULL COMMENT 'Matched rule version at request creation';
