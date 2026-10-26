CREATE TABLE dg_security_settings (
 setting_key VARCHAR(64) PRIMARY KEY,
 setting_value INT NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO dg_security_settings(setting_key,setting_value) VALUES ('agent_calls_per_minute',60),('agent_rows_per_minute',3000);
