-- Durable security counters, notification receipts and operational events.
CREATE TABLE dg_login_lock (
 username VARCHAR(64) PRIMARY KEY COMMENT 'Only the account name; passwords are never stored',
 failures INT NOT NULL DEFAULT 0,
 first_failed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 locked_until DATETIME(3) NULL,
 KEY idx_login_until(locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Failed login attempt window and temporary account lockout';

CREATE TABLE dg_agent_minute_usage (
 agent_id CHAR(36) NOT NULL,
 minute_bucket DATETIME NOT NULL,
 calls INT NOT NULL DEFAULT 0,
 requested_rows INT NOT NULL DEFAULT 0,
 PRIMARY KEY(agent_id,minute_bucket),
 CONSTRAINT fk_quota_agent FOREIGN KEY(agent_id) REFERENCES dg_agents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Atomic Agent request-per-minute and row-request quotas';

CREATE TABLE dg_notifications (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL,
 request_id CHAR(36) NOT NULL,
 event_type VARCHAR(24) NOT NULL,
 content VARCHAR(180) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 read_at DATETIME(3) NULL,
 UNIQUE KEY uq_notify(username,request_id,event_type),
 INDEX idx_notice_user(username,read_at,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable internal approval and reminder notices';
