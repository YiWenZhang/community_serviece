-- 1.2.0增量升级：先备份，选择已有业务库执行；不建库、不删除业务数据、不修改密码。
-- DDL会隐式提交，可重复执行以恢复中断。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS app_schema_version(version INT PRIMARY KEY,applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS user_community(user_id BIGINT UNSIGNED NOT NULL,community_id BIGINT UNSIGNED NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',created_by BIGINT UNSIGNED,updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),PRIMARY KEY(user_id,community_id),FOREIGN KEY(user_id) REFERENCES sys_user(id),FOREIGN KEY(community_id) REFERENCES community(id),CHECK(status IN ('ACTIVE','INACTIVE')));
CREATE TABLE IF NOT EXISTS community_profile(community_id BIGINT UNSIGNED PRIMARY KEY,description TEXT,icon_data MEDIUMBLOB,icon_mime VARCHAR(64),FOREIGN KEY(community_id) REFERENCES community(id));
INSERT IGNORE INTO user_community(user_id,community_id) SELECT id,community_id FROM sys_user WHERE NOT EXISTS(SELECT 1 FROM app_schema_version WHERE version=2);
INSERT IGNORE INTO sys_permission(code,name,resource_type) VALUES('system:community:manage','管理全部社区及分配','ACTION');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id) SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.code='ADMIN' AND p.code IN ('system:community:manage','elder:read','elder:create','elder:update','vaccine:verify','notice:read','notice:create','notice:receive','followup:read','followup:update','feedback:create','file:upload','file:read','report:read:community','report:export:community') AND NOT EXISTS(SELECT 1 FROM app_schema_version WHERE version=2);

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='elder_profile' AND constraint_name='fk_elder_owner_scope'),'ALTER TABLE elder_profile DROP FOREIGN KEY fk_elder_owner_scope, ADD CONSTRAINT fk_elder_owner_scope_v2 FOREIGN KEY(owner_user_id) REFERENCES sys_user(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='elder_profile' AND constraint_name='fk_elder_register_scope'),'ALTER TABLE elder_profile DROP FOREIGN KEY fk_elder_register_scope, ADD CONSTRAINT fk_elder_register_scope_v2 FOREIGN KEY(registered_by) REFERENCES sys_user(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='notice_recipient' AND constraint_name='fk_rec_owner_scope'),'ALTER TABLE notice_recipient DROP FOREIGN KEY fk_rec_owner_scope, ADD CONSTRAINT fk_rec_owner_scope_v2 FOREIGN KEY(owner_user_id) REFERENCES sys_user(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='notice_recipient' AND constraint_name='fk_rec_issue_owner_scope'),'ALTER TABLE notice_recipient DROP FOREIGN KEY fk_rec_issue_owner_scope, ADD CONSTRAINT fk_rec_issue_owner_scope_v2 FOREIGN KEY(owner_at_issue) REFERENCES sys_user(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='followup_task' AND constraint_name='fk_ft_assignee_scope'),'ALTER TABLE followup_task DROP FOREIGN KEY fk_ft_assignee_scope, ADD CONSTRAINT fk_ft_assignee_scope_v2 FOREIGN KEY(assignee_id) REFERENCES sys_user(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='report_job' AND constraint_name='fk_rj_scope'),'ALTER TABLE report_job DROP FOREIGN KEY fk_rj_scope, ADD CONSTRAINT fk_rj_scope_v2 FOREIGN KEY(scope_owner_id) REFERENCES sys_user(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='report_job' AND constraint_name='fk_rj_requester'),'ALTER TABLE report_job DROP FOREIGN KEY fk_rj_requester, ADD CONSTRAINT fk_rj_requester_v2 FOREIGN KEY(requested_by) REFERENCES sys_user(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='notice_recipient' AND constraint_name='fk_rec_elder_scope'),'ALTER TABLE notice_recipient DROP FOREIGN KEY fk_rec_elder_scope, ADD CONSTRAINT fk_rec_elder_scope_v2 FOREIGN KEY(elder_id) REFERENCES elder_profile(id)','SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

CREATE TABLE IF NOT EXISTS elder_global_sequence(register_year SMALLINT UNSIGNED PRIMARY KEY,next_value BIGINT UNSIGNED NOT NULL,version INT NOT NULL DEFAULT 0);
INSERT IGNORE INTO elder_global_sequence(register_year,next_value) SELECT register_year,MAX(next_value) FROM elder_no_sequence GROUP BY register_year;
INSERT IGNORE INTO app_schema_version(version) VALUES(2);
