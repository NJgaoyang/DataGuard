-- Persist reviewed SQL structure only; never original bound parameters or literal values.
ALTER TABLE dg_requests
 ADD COLUMN sql_template TEXT NULL COMMENT '敏感字面量脱敏后的 SQL 模板，旧记录为空';