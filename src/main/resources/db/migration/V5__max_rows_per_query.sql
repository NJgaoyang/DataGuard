INSERT INTO dg_security_settings(setting_key,setting_value) VALUES ('max_rows_per_query',200) ON DUPLICATE KEY UPDATE setting_value=setting_value;
