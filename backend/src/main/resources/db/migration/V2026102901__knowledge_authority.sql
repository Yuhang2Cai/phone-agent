-- AG08：知识权威与撤回事实独立于用户记忆；时间为数据库UTC时刻的毫秒值。
CREATE TABLE knowledge_releases (
 release_id VARCHAR(64) PRIMARY KEY,
 manifest_sha VARCHAR(64) NOT NULL,
 approval_record LONGTEXT NOT NULL,
 created_at_ms BIGINT NOT NULL
);
-- 条目快照不可覆盖；相同条目版本在不同发布中必须保持相同完整摘要，由事务入口校验。
CREATE TABLE knowledge_entries (
 release_id VARCHAR(64) NOT NULL,
 entry_id VARCHAR(64) NOT NULL,
 entry_version VARCHAR(64) NOT NULL,
 entry_sha VARCHAR(64) NOT NULL,
 entry_json LONGTEXT NOT NULL,
 valid_from_ms BIGINT NOT NULL,
 valid_until_ms BIGINT,
 PRIMARY KEY (release_id, entry_id),
 FOREIGN KEY (release_id) REFERENCES knowledge_releases(release_id)
);
-- 单行锁是发布、撤回、绑定与交付的唯一串行点，不在锁内访问供应商。
CREATE TABLE knowledge_head (
 singleton_id INTEGER PRIMARY KEY,
 active_release VARCHAR(64),
 generation BIGINT NOT NULL
);
INSERT INTO knowledge_head(singleton_id, active_release, generation) VALUES (1, NULL, 0);
-- 不随活动发布回滚，防止旧发布复活已经禁用的版本。
CREATE TABLE knowledge_revocations (
 entry_id VARCHAR(64) NOT NULL,
 entry_version VARCHAR(64) NOT NULL,
 reason LONGTEXT NOT NULL,
 revoked_at_ms BIGINT NOT NULL,
 PRIMARY KEY(entry_id, entry_version)
);
CREATE TABLE knowledge_bindings (
 execution_key VARCHAR(255) PRIMARY KEY,
 user_id VARCHAR(100) NOT NULL,
 session_id VARCHAR(100) NOT NULL,
 run_id VARCHAR(100) NOT NULL,
 task_id VARCHAR(100) NOT NULL,
 release_id VARCHAR(64) NOT NULL,
 sources_json LONGTEXT NOT NULL,
 FOREIGN KEY(release_id) REFERENCES knowledge_releases(release_id)
);
CREATE INDEX knowledge_binding_run ON knowledge_bindings(user_id,session_id,run_id);
-- 每次重新交付独立记录许可；事件提交失败时同事务回滚，不发布SSE。
CREATE TABLE knowledge_delivery_permits (
 permit_id VARCHAR(64) PRIMARY KEY,
 execution_key VARCHAR(255) NOT NULL,
 delivery_ref VARCHAR(255) NOT NULL,
 generation BIGINT NOT NULL,
 permitted_at_ms BIGINT NOT NULL,
 sources_json LONGTEXT NOT NULL,
 FOREIGN KEY(execution_key) REFERENCES knowledge_bindings(execution_key)
);
