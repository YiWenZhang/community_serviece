-- v1.5.0: identity/contact details are stored only in AES-GCM ciphertext.
-- Existing rows stay NULL until explicitly completed. No identity-card column is created.
-- DDL implicitly commits; back up before running. Repeated execution is supported.
SET NAMES utf8mb4;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='elder_profile' AND column_name='personal_info_cipher'),'SELECT 1','ALTER TABLE elder_profile ADD COLUMN personal_info_cipher BLOB NULL COMMENT ''AES-GCM encrypted name, phone, address and family contacts JSON''');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='elder_profile' AND column_name='name_hash'),'SELECT 1','ALTER TABLE elder_profile ADD COLUMN name_hash BINARY(32) NULL COMMENT ''Domain separated HMAC for exact name search''');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='elder_profile' AND column_name='phone_hash'),'SELECT 1','ALTER TABLE elder_profile ADD COLUMN phone_hash BINARY(32) NULL COMMENT ''Domain separated HMAC for exact phone search''');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='elder_profile' AND index_name='idx_elder_name_hash'),'SELECT 1','ALTER TABLE elder_profile ADD INDEX idx_elder_name_hash(community_id,name_hash)');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='elder_profile' AND index_name='idx_elder_phone_hash'),'SELECT 1','ALTER TABLE elder_profile ADD INDEX idx_elder_phone_hash(community_id,phone_hash)');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
INSERT IGNORE INTO app_schema_version(version) VALUES(3);
