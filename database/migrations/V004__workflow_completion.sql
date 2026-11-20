-- v1.6.0. Back up first; MySQL DDL commits implicitly. Safe to run again.
SET NAMES utf8mb4;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='community_profile' AND column_name='sms_config_cipher'),'SELECT 1','ALTER TABLE community_profile ADD COLUMN sms_config_cipher BLOB NULL COMMENT ''AES-GCM encrypted SMS gateway configuration''');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='community_profile' AND column_name='sms_version'),'SELECT 1','ALTER TABLE community_profile ADD COLUMN sms_version INT UNSIGNED NOT NULL DEFAULT 0');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='delivery_attempt' AND column_name='family_name_cipher'),'SELECT 1','ALTER TABLE delivery_attempt ADD COLUMN family_name_cipher BLOB NULL COMMENT ''AES-GCM encrypted family relay name''');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
INSERT IGNORE INTO community_profile(community_id) SELECT id FROM community;
INSERT IGNORE INTO app_schema_version(version) VALUES(4);
