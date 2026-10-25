-- DataGuard V1: independent metadata and enforcement schema (MySQL 8.4, utf8mb4).
CREATE TABLE dg_users (
 id CHAR(36) PRIMARY KEY COMMENT '用户 UUID',
 username VARCHAR(64) NOT NULL UNIQUE COMMENT '唯一用户名',
 display_name VARCHAR(100) NOT NULL COMMENT '展示名称',
 password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码摘要',
 role_code VARCHAR(16) NOT NULL COMMENT 'ADMIN, APPROVER, READER',
 enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否可以使用系统',
 must_change_password BOOLEAN NOT NULL DEFAULT TRUE COMMENT '首次登录必须修改复杂密码',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CHECK (role_code IN ('ADMIN','APPROVER','READER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部人员及审批账号';

CREATE TABLE dg_sources (
 id CHAR(36) PRIMARY KEY COMMENT '业务数据源 UUID',
 name VARCHAR(128) NOT NULL COMMENT '数据源名称',
 type VARCHAR(16) NOT NULL COMMENT 'MYSQL 或 STARROCKS',
 host VARCHAR(253) NOT NULL COMMENT '数据库地址',
 port INT NOT NULL COMMENT '数据库端口',
 database_name VARCHAR(64) NOT NULL COMMENT '数据库名称',
 read_username VARCHAR(100) NOT NULL COMMENT '目标库最小化 SELECT 账号',
 password_ciphertext TEXT NOT NULL COMMENT 'AES-GCM加密的数据库密码',
 enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否开放读取',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CHECK (type IN ('MYSQL','STARROCKS')),
 CHECK (port BETWEEN 1 AND 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MySQL或StarRocks业务源';

CREATE TABLE dg_source_tables (
 source_id CHAR(36) NOT NULL,
 table_name VARCHAR(64) NOT NULL COMMENT '允许开放给AI的表',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(source_id,table_name),
 FOREIGN KEY(source_id) REFERENCES dg_sources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台显式表白名单';

CREATE TABLE dg_source_columns (
 source_id CHAR(36) NOT NULL,
 table_name VARCHAR(64) NOT NULL,
 column_name VARCHAR(64) NOT NULL COMMENT '可暴露给Agent的字段',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(source_id,table_name,column_name),
 FOREIGN KEY(source_id,table_name) REFERENCES dg_source_tables(source_id,table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台显式字段白名单';

CREATE TABLE dg_agents (
 id CHAR(36) PRIMARY KEY,
 owner_user_id CHAR(36) NOT NULL COMMENT 'Agent归属用户',
 name VARCHAR(120) NOT NULL COMMENT 'Agent名称',
 token_hash CHAR(64) NOT NULL UNIQUE COMMENT 'API Token SHA256摘要，不存储明文',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 expires_at DATETIME(3) NOT NULL,
 revoked_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(owner_user_id) REFERENCES dg_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立Agent身份及有效期';

CREATE TABLE dg_agent_tables (
 agent_id CHAR(36) NOT NULL,
 source_id CHAR(36) NOT NULL,
 table_name VARCHAR(64) NOT NULL,
 PRIMARY KEY(agent_id,source_id,table_name),
 FOREIGN KEY(agent_id) REFERENCES dg_agents(id),
 FOREIGN KEY(source_id,table_name) REFERENCES dg_source_tables(source_id,table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent表授权（独立于平台白名单）';

CREATE TABLE dg_agent_columns (
 agent_id CHAR(36) NOT NULL,
 source_id CHAR(36) NOT NULL,
 table_name VARCHAR(64) NOT NULL,
 column_name VARCHAR(64) NOT NULL,
 PRIMARY KEY(agent_id,source_id,table_name,column_name),
 FOREIGN KEY(agent_id,source_id,table_name) REFERENCES dg_agent_tables(agent_id,source_id,table_name),
 FOREIGN KEY(source_id,table_name,column_name) REFERENCES dg_source_columns(source_id,table_name,column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent字段白名单（与平台字段取交集）';

CREATE TABLE dg_rules (
 id CHAR(36) PRIMARY KEY,
 name VARCHAR(120) NOT NULL COMMENT '规则名称',
 source_id CHAR(36) NULL COMMENT 'NULL表示所有源',
 agent_id CHAR(36) NULL COMMENT 'NULL表示所有Agent',
 table_name VARCHAR(64) NOT NULL COMMENT '表名称或星号通配',
 column_name VARCHAR(64) NULL COMMENT '可选敏感字段触发条件',
 min_rows INT NOT NULL DEFAULT 1,
 max_rows INT NOT NULL DEFAULT 200 COMMENT '每次请求适用的行数区间',
 action VARCHAR(16) NOT NULL COMMENT 'ALLOW、APPROVAL或DENY',
 approver_id CHAR(36) NULL COMMENT '当动作需要审批时的指定审批人',
 priority INT NOT NULL DEFAULT 100,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(source_id) REFERENCES dg_sources(id),
 FOREIGN KEY(agent_id) REFERENCES dg_agents(id),
 FOREIGN KEY(approver_id) REFERENCES dg_users(id),
 CHECK(action IN ('ALLOW','APPROVAL','DENY')),
 CHECK(min_rows>=1 AND max_rows BETWEEN min_rows AND 200),
 CHECK(priority>=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时生效的AI访问规则；默认拒绝';

CREATE TABLE dg_requests (
 id CHAR(36) PRIMARY KEY COMMENT '请求或审批编号',
 agent_id CHAR(36) NOT NULL,
 owner_id CHAR(36) NOT NULL,
 source_id CHAR(36) NOT NULL,
 table_name VARCHAR(64) NOT NULL,
 columns_csv VARCHAR(2048) NOT NULL COMMENT '请求字段快照（不存数据值）',
 requested_rows INT NOT NULL,
 query_hash CHAR(64) NOT NULL COMMENT '包含筛选等参数的固定请求指纹',
 rule_id CHAR(36) NULL,
 action VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL COMMENT 'PENDING、APPROVED、EXECUTING、SUCCESS、DENIED、REJECTED、FAILED',
 approver_id CHAR(36) NULL,
 reason VARCHAR(512) NULL COMMENT '审批意见或拒绝原因，不存业务数据',
 returned_rows INT NULL,
 duration_ms BIGINT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 expires_at DATETIME(3) NOT NULL COMMENT '单次授权过期时间',
 decided_at DATETIME(3) NULL,
 finished_at DATETIME(3) NULL,
 FOREIGN KEY(agent_id) REFERENCES dg_agents(id),
 FOREIGN KEY(owner_id) REFERENCES dg_users(id),
 FOREIGN KEY(source_id) REFERENCES dg_sources(id),
 FOREIGN KEY(rule_id) REFERENCES dg_rules(id),
 FOREIGN KEY(approver_id) REFERENCES dg_users(id),
 INDEX idx_request_agent_hash(agent_id,query_hash,status,created_at),
 INDEX idx_request_reviewer(approver_id,status,created_at),
 INDEX idx_request_recent(created_at,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单次AI数据读取申请及真实执行';

CREATE TABLE dg_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 actor VARCHAR(120) NOT NULL COMMENT '内部用户或Agent',
 event_type VARCHAR(64) NOT NULL,
 request_id CHAR(36) NULL,
 object_ref VARCHAR(256) NOT NULL,
 result VARCHAR(20) NOT NULL,
 message VARCHAR(512) NOT NULL DEFAULT '' COMMENT '不包含业务查询结果或明文密码',
 INDEX idx_audit_at(event_at),
 INDEX idx_audit_request(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防止业务数据泄漏的结构化操作审计';
