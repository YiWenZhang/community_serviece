-- 增量迁移：仅创建AI表并首次授予内置角色AI权限；不重建原业务库。
CREATE TABLE IF NOT EXISTS ai_schema_version (version INT PRIMARY KEY, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS ai_provider (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, community_id BIGINT UNSIGNED NOT NULL,
 name VARCHAR(80) NOT NULL, kind VARCHAR(16) NOT NULL, api_format VARCHAR(16) NOT NULL,
 base_url VARCHAR(500) NOT NULL, model VARCHAR(160) NOT NULL, api_key_cipher BLOB,
 active BOOLEAN NOT NULL DEFAULT FALSE, version INT NOT NULL DEFAULT 0,
 created_by BIGINT UNSIGNED NOT NULL, updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 INDEX idx_ai_provider (community_id,kind,active)
);
CREATE TABLE IF NOT EXISTS ai_document (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, community_id BIGINT UNSIGNED NOT NULL,
 title VARCHAR(200) NOT NULL, category VARCHAR(32) NOT NULL, issuer VARCHAR(160), policy_no VARCHAR(100),
 effective_date DATE, expiry_date DATE, is_demo BOOLEAN NOT NULL DEFAULT FALSE,
 safe_name VARCHAR(200) NOT NULL, storage_key VARCHAR(64) NOT NULL, sha256 CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', error_summary VARCHAR(300), extracted_text MEDIUMTEXT,
 summary TEXT, chunk_count INT NOT NULL DEFAULT 0, lease_token VARCHAR(36), lease_until DATETIME(3),
 version INT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL,
 created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 INDEX idx_ai_document (community_id,status), INDEX idx_ai_document_hash (community_id,sha256)
);
CREATE TABLE IF NOT EXISTS ai_chunk (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, document_id BIGINT UNSIGNED NOT NULL, community_id BIGINT UNSIGNED NOT NULL,
 ordinal INT NOT NULL, section VARCHAR(200) NOT NULL, page_no INT, start_offset INT NOT NULL, end_offset INT NOT NULL,
 content TEXT NOT NULL, summary TEXT NOT NULL, keywords TEXT NOT NULL,
 embedding_json MEDIUMTEXT NOT NULL, embedding_space VARCHAR(64) NOT NULL,
 FOREIGN KEY (document_id) REFERENCES ai_document(id) ON DELETE CASCADE,
 UNIQUE KEY uk_ai_chunk (document_id,ordinal), INDEX idx_ai_chunk_scope (community_id,embedding_space)
);
CREATE TABLE IF NOT EXISTS ai_conversation (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, community_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 title VARCHAR(100) NOT NULL, busy_token VARCHAR(36), busy_until DATETIME(3),
 created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 INDEX idx_ai_conversation (community_id,user_id)
);
CREATE TABLE IF NOT EXISTS ai_message (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, conversation_id BIGINT UNSIGNED NOT NULL,
 request_key VARCHAR(36) NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL, citations_json MEDIUMTEXT NOT NULL, model VARCHAR(160),
 created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id) ON DELETE CASCADE, UNIQUE KEY uk_ai_message_request (conversation_id,request_key)
);
INSERT IGNORE INTO sys_permission(code,name,resource_type) VALUES
 ('ai:knowledge:manage','维护政策知识库','ACTION'),('ai:chat','使用政策问答','ACTION'),('ai:model:manage','管理AI模型配置','ACTION');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
 WHERE ((r.code='ADMIN' AND p.code IN ('ai:knowledge:manage','ai:chat','ai:model:manage'))
 OR (r.code='GRID_WORKER' AND p.code='ai:chat')) AND NOT EXISTS (SELECT 1 FROM ai_schema_version WHERE version=1);
INSERT IGNORE INTO ai_schema_version(version) VALUES(1);
