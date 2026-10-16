-- 社区老年服务系统 非AI模块 初始化脚本 v1.0
-- 适用 MySQL 8.0.16及以上（已按本机8.0.42测试）；兼容MySQL 8.4。
-- 编码 UTF-8，时区 UTC；31张业务表，无AI政策、向量和问答表。
-- 仅首次初始化使用。数据库已存在时CREATE DATABASE会失败，应立即停止。
-- 无DROP/TRUNCATE，不覆盖其他数据库，不修改root密码，不创建数据库账号。
-- DDL会隐式提交；中途失败不可当作整体回滚。禁止忽略错误继续执行。
-- 示例全部虚构；应用用户禁用且没有可登录密码。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';
CREATE DATABASE community_elder_service CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE community_elder_service;


-- 社区
CREATE TABLE community (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
code VARCHAR(32) NOT NULL COMMENT '社区代码',
name VARCHAR(80) NOT NULL COMMENT '社区名称',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_community_code (code), CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='社区';


-- 社区网格
CREATE TABLE grid (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
code VARCHAR(32) NOT NULL COMMENT '网格代码',
name VARCHAR(80) NOT NULL COMMENT '网格名称',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_grid_code (community_id,code), UNIQUE KEY uk_grid_scope (id,community_id),
CONSTRAINT fk_grid_community FOREIGN KEY (community_id) REFERENCES community(id),
CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='社区网格';


-- 工作人员账号 不保存手机号
CREATE TABLE sys_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区',
username VARCHAR(64) NOT NULL COMMENT '登录账号',
password_hash VARCHAR(255) NOT NULL COMMENT '密码散列 示例为不可登录标记',
display_name VARCHAR(64) NOT NULL COMMENT '工作人员显示名',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE DISABLED',
failed_login_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '连续失败次数',
locked_until DATETIME(3) NULL COMMENT '锁定截止 UTC',
last_login_at DATETIME(3) NULL COMMENT '最近登录 UTC',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_user_username (username), UNIQUE KEY uk_user_scope (id,community_id),
KEY idx_user_community_status (community_id,status),
CONSTRAINT fk_user_community FOREIGN KEY (community_id) REFERENCES community(id),
CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作人员账号 不保存手机号';


-- 角色
CREATE TABLE sys_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
code VARCHAR(32) NOT NULL COMMENT '角色代码',
name VARCHAR(64) NOT NULL COMMENT '角色名称',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_role_code (code), CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';


-- 接口及操作权限
CREATE TABLE sys_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
code VARCHAR(80) NOT NULL COMMENT '权限代码',
name VARCHAR(80) NOT NULL COMMENT '权限名称',
resource_type VARCHAR(16) NOT NULL COMMENT 'MENU ACTION API',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_permission_code (code), CHECK (resource_type IN ('MENU','ACTION','API'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口及操作权限';


-- 用户角色关系
CREATE TABLE sys_user_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
user_id BIGINT UNSIGNED NOT NULL COMMENT '用户',
role_id BIGINT UNSIGNED NOT NULL COMMENT '角色',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
UNIQUE KEY uk_user_role (user_id,role_id),
CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系';


-- 角色权限关系
CREATE TABLE sys_role_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
role_id BIGINT UNSIGNED NOT NULL COMMENT '角色',
permission_id BIGINT UNSIGNED NOT NULL COMMENT '权限',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
UNIQUE KEY uk_role_permission (role_id,permission_id),
CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关系';


-- 老人编号年度序列 禁止MAX加1
CREATE TABLE elder_no_sequence (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
register_year SMALLINT UNSIGNED NOT NULL COMMENT '登记年份',
next_value BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '下一序号 最少三位可扩展',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_no_sequence (community_id,register_year),
CONSTRAINT fk_seq_community FOREIGN KEY (community_id) REFERENCES community(id),
CHECK (register_year BETWEEN 1900 AND 9999), CHECK (next_value >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='老人编号年度序列 禁止MAX加1';


-- 老人脱敏档案 无姓名证件手机及家庭地址
CREATE TABLE elder_profile (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
grid_id BIGINT UNSIGNED NOT NULL COMMENT '同社区网格',
owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '当前负责网格员',
elder_no_cipher VARBINARY(256) NOT NULL COMMENT 'AES256GCM nonce12加密文及tag16',
elder_no_hash BINARY(32) NOT NULL COMMENT '编号HMAC-SHA256精确检索',
key_version SMALLINT UNSIGNED NOT NULL COMMENT '密钥版本',
register_year SMALLINT UNSIGNED NOT NULL COMMENT '登记年份',
sex VARCHAR(16) NOT NULL COMMENT 'MALE FEMALE UNKNOWN',
birth_year SMALLINT UNSIGNED NOT NULL COMMENT '出生年份',
birth_month TINYINT UNSIGNED NOT NULL COMMENT '出生月份 不采集精确日期',
registered_by BIGINT UNSIGNED NOT NULL COMMENT '初始登记人',
info_updated_at DATETIME(3) NOT NULL COMMENT '业务信息更新时间 UTC',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE ARCHIVED',
remark VARCHAR(500) NULL COMMENT '脱敏业务备注',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_elder_no (community_id,elder_no_hash), UNIQUE KEY uk_elder_scope (id,community_id),
KEY idx_elder_owner (community_id,owner_user_id,status,id),
KEY idx_elder_birthday (community_id,birth_month,status,id), KEY idx_elder_grid (grid_id,status,id),
CONSTRAINT fk_elder_community FOREIGN KEY (community_id) REFERENCES community(id),
CONSTRAINT fk_elder_grid_scope FOREIGN KEY (grid_id,community_id) REFERENCES grid(id,community_id),
CONSTRAINT fk_elder_owner_scope FOREIGN KEY (owner_user_id,community_id) REFERENCES sys_user(id,community_id),
CONSTRAINT fk_elder_register_scope FOREIGN KEY (registered_by,community_id) REFERENCES sys_user(id,community_id),
CHECK (birth_month BETWEEN 1 AND 12), CHECK (birth_year BETWEEN 1800 AND 9999),
CHECK (register_year BETWEEN 1900 AND 9999), CHECK (key_version >= 1),
CHECK (sex IN ('MALE','FEMALE','UNKNOWN')), CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='老人脱敏档案 无姓名证件手机及家庭地址';


-- 老人归属移交历史
CREATE TABLE elder_assignment_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
elder_id BIGINT UNSIGNED NOT NULL COMMENT '老人',
old_grid_id BIGINT UNSIGNED NULL COMMENT '旧网格 首次登记为空',
new_grid_id BIGINT UNSIGNED NOT NULL COMMENT '新网格',
old_owner_id BIGINT UNSIGNED NULL COMMENT '旧负责人',
new_owner_id BIGINT UNSIGNED NOT NULL COMMENT '新负责人',
effective_at DATETIME(3) NOT NULL COMMENT '生效 UTC',
reason VARCHAR(300) NOT NULL COMMENT '移交或登记原因',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
KEY idx_assignment_elder_time (elder_id,effective_at,id),
CONSTRAINT fk_ah_elder FOREIGN KEY (elder_id) REFERENCES elder_profile(id),
CONSTRAINT fk_ah_old_grid FOREIGN KEY (old_grid_id) REFERENCES grid(id),
CONSTRAINT fk_ah_new_grid FOREIGN KEY (new_grid_id) REFERENCES grid(id),
CONSTRAINT fk_ah_old_owner FOREIGN KEY (old_owner_id) REFERENCES sys_user(id),
CONSTRAINT fk_ah_new_owner FOREIGN KEY (new_owner_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='老人归属移交历史';


-- 服务需求字典
CREATE TABLE service_need (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
code VARCHAR(32) NOT NULL COMMENT '需求代码',
name VARCHAR(64) NOT NULL COMMENT '需求名称',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_need_code (code), CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='服务需求字典';


-- 老人服务需求关系
CREATE TABLE elder_service_need (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
elder_id BIGINT UNSIGNED NOT NULL COMMENT '老人',
need_id BIGINT UNSIGNED NOT NULL COMMENT '服务需求',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_elder_need (elder_id,need_id), KEY idx_need_elder (need_id,status,elder_id),
CONSTRAINT fk_en_elder FOREIGN KEY (elder_id) REFERENCES elder_profile(id),
CONSTRAINT fk_en_need FOREIGN KEY (need_id) REFERENCES service_need(id), CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='老人服务需求关系';


-- 疫苗产品字典
CREATE TABLE vaccine_product (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
code VARCHAR(32) NOT NULL COMMENT '产品代码',
name VARCHAR(80) NOT NULL COMMENT '产品名称',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_vaccine_code (code), CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='疫苗产品字典';


-- 疫苗年度周期及接种阶段
CREATE TABLE vaccine_campaign (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
vaccine_id BIGINT UNSIGNED NOT NULL COMMENT '疫苗产品',
cycle_key VARCHAR(32) NOT NULL COMMENT '周期 如2026',
dose_stage VARCHAR(32) NOT NULL COMMENT '阶段 如ANNUAL BASIC BOOSTER',
name VARCHAR(100) NOT NULL COMMENT '周期名称',
start_date DATE NOT NULL COMMENT '开始日期',
end_date DATE NOT NULL COMMENT '结束日期',
eligibility_rule_ref VARCHAR(200) NULL COMMENT '人工核验依据编号 非AI引用',
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE INACTIVE',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_campaign_cycle (community_id,vaccine_id,cycle_key,dose_stage),
CONSTRAINT fk_vc_community FOREIGN KEY (community_id) REFERENCES community(id),
CONSTRAINT fk_vc_product FOREIGN KEY (vaccine_id) REFERENCES vaccine_product(id),
CHECK (start_date <= end_date), CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='疫苗年度周期及接种阶段';


-- 老人具体疫苗周期资格及当前接种状态
CREATE TABLE elder_vaccine_status (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
elder_id BIGINT UNSIGNED NOT NULL COMMENT '老人',
campaign_id BIGINT UNSIGNED NOT NULL COMMENT '疫苗周期',
eligibility VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'ELIGIBLE INELIGIBLE UNKNOWN',
eligibility_checked_by BIGINT UNSIGNED NULL COMMENT '资格核验人',
eligibility_checked_at DATETIME(3) NULL COMMENT '资格核验 UTC',
vaccination_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'NOT_VACCINATED PARTIAL COMPLETED UNKNOWN',
appointment_date DATE NULL COMMENT '人工确认预约日期',
status_effective_at DATETIME(3) NOT NULL COMMENT '状态生效 UTC',
source VARCHAR(16) NOT NULL COMMENT 'MANUAL EXTERNAL',
source_ref VARCHAR(100) NULL COMMENT '外部数据引用',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_elder_campaign (elder_id,campaign_id),
KEY idx_vaccine_target (campaign_id,eligibility,vaccination_status,elder_id),
CONSTRAINT fk_evs_elder FOREIGN KEY (elder_id) REFERENCES elder_profile(id),
CONSTRAINT fk_evs_campaign FOREIGN KEY (campaign_id) REFERENCES vaccine_campaign(id),
CONSTRAINT fk_evs_checker FOREIGN KEY (eligibility_checked_by) REFERENCES sys_user(id),
CHECK (eligibility IN ('ELIGIBLE','INELIGIBLE','UNKNOWN')),
CHECK (vaccination_status IN ('NOT_VACCINATED','PARTIAL','COMPLETED','UNKNOWN')),
CHECK (source IN ('MANUAL','EXTERNAL')),
CHECK (eligibility='UNKNOWN' OR (eligibility_checked_by IS NOT NULL AND eligibility_checked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='老人具体疫苗周期资格及当前接种状态';


-- 资格及接种历史事实 纠错追加记录
CREATE TABLE vaccination_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
elder_vaccine_status_id BIGINT UNSIGNED NOT NULL COMMENT '老人周期状态',
action VARCHAR(16) NOT NULL COMMENT 'VACCINATION ASSESSMENT CORRECTION',
vaccinated_on DATE NULL COMMENT '实际接种日期',
dose_no TINYINT UNSIGNED NULL COMMENT '针次',
resulting_status VARCHAR(24) NOT NULL COMMENT '变更后接种状态',
eligibility_snapshot VARCHAR(16) NOT NULL COMMENT '变更后资格',
effective_at DATETIME(3) NOT NULL COMMENT '事实生效 UTC',
verified_by BIGINT UNSIGNED NOT NULL COMMENT '核验人员',
source VARCHAR(16) NOT NULL COMMENT 'MANUAL EXTERNAL',
external_record_ref VARCHAR(100) NULL COMMENT '外部幂等编号',
supersedes_record_id BIGINT UNSIGNED NULL COMMENT '被更正的历史记录',
reason VARCHAR(300) NULL COMMENT '核验或更正说明',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
KEY idx_vr_effective (elder_vaccine_status_id,effective_at,id), UNIQUE KEY uk_vr_external (source,external_record_ref),
CONSTRAINT fk_vr_status FOREIGN KEY (elder_vaccine_status_id) REFERENCES elder_vaccine_status(id),
CONSTRAINT fk_vr_verifier FOREIGN KEY (verified_by) REFERENCES sys_user(id),
CONSTRAINT fk_vr_supersedes FOREIGN KEY (supersedes_record_id) REFERENCES vaccination_record(id),
CHECK (action IN ('VACCINATION','ASSESSMENT','CORRECTION')),
CHECK (resulting_status IN ('NOT_VACCINATED','PARTIAL','COMPLETED','UNKNOWN')),
CHECK (eligibility_snapshot IN ('ELIGIBLE','INELIGIBLE','UNKNOWN')), CHECK (source IN ('MANUAL','EXTERNAL')),
CHECK (dose_no IS NULL OR dose_no >= 1),
CHECK (action <> 'VACCINATION' OR (vaccinated_on IS NOT NULL AND dose_no IS NOT NULL)),
CHECK (action <> 'CORRECTION' OR (supersedes_record_id IS NOT NULL AND reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资格及接种历史事实 纠错追加记录';


-- 通知自动生成规则
CREATE TABLE notice_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
code VARCHAR(32) NOT NULL COMMENT '规则代码',
name VARCHAR(80) NOT NULL COMMENT '规则名称',
notice_type VARCHAR(24) NOT NULL COMMENT 'VACCINE BIRTHDAY LECTURE OTHER',
trigger_kind VARCHAR(24) NOT NULL COMMENT 'BIRTH_MONTH VACCINE_STATUS MANUAL',
rule_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '规则版本',
config_json JSON NOT NULL COMMENT '受控参数 禁止SQL或脚本',
enabled TINYINT NOT NULL DEFAULT 0 COMMENT '0停用 1启用',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_rule_code (community_id,code),
CONSTRAINT fk_nr_community FOREIGN KEY (community_id) REFERENCES community(id),
CHECK (notice_type IN ('VACCINE','BIRTHDAY','LECTURE','OTHER')),
CHECK (trigger_kind IN ('BIRTH_MONTH','VACCINE_STATUS','MANUAL')), CHECK (rule_version >= 1), CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知自动生成规则';


-- 通知批次 内容与名单激活后冻结
CREATE TABLE notice (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
notice_no VARCHAR(40) NOT NULL COMMENT '业务编号',
type VARCHAR(24) NOT NULL COMMENT '通知类型',
title VARCHAR(120) NOT NULL COMMENT '标题',
content TEXT NOT NULL COMMENT '正文 不含禁止采集的信息',
source VARCHAR(16) NOT NULL COMMENT 'MANUAL RULE',
rule_id BIGINT UNSIGNED NULL COMMENT '来源规则',
rule_version INT UNSIGNED NULL COMMENT '当次规则版本',
rule_snapshot_json JSON NULL COMMENT '规则快照',
period_key VARCHAR(32) NOT NULL COMMENT '目标周期',
campaign_id BIGINT UNSIGNED NULL COMMENT '疫苗通知对应周期',
scheduled_at DATETIME(3) NULL COMMENT '计划 UTC',
active_at DATETIME(3) NULL COMMENT '激活 UTC',
status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT ACTIVE COMPLETED CANCELLED',
cancel_reason VARCHAR(300) NULL COMMENT '取消原因',
generation_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规则周期批次幂等键',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_notice_no (community_id,notice_no), UNIQUE KEY uk_notice_scope (id,community_id),
UNIQUE KEY uk_notice_generation (generation_key), KEY idx_notice_status (community_id,status,created_at,id),
CONSTRAINT fk_notice_community FOREIGN KEY (community_id) REFERENCES community(id),
CONSTRAINT fk_notice_rule FOREIGN KEY (rule_id) REFERENCES notice_rule(id),
CONSTRAINT fk_notice_campaign FOREIGN KEY (campaign_id) REFERENCES vaccine_campaign(id),
CHECK (type IN ('VACCINE','BIRTHDAY','LECTURE','OTHER')), CHECK (source IN ('MANUAL','RULE')),
CHECK (status IN ('DRAFT','ACTIVE','COMPLETED','CANCELLED')),
CHECK (source <> 'RULE' OR (rule_id IS NOT NULL AND rule_version IS NOT NULL AND rule_snapshot_json IS NOT NULL)),
CHECK (type <> 'VACCINE' OR campaign_id IS NOT NULL),
CHECK (status NOT IN ('ACTIVE','COMPLETED') OR active_at IS NOT NULL),
CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知批次 内容与名单激活后冻结';


-- 每位老人对应通知对象 与通知批次分离
CREATE TABLE notice_recipient (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
notice_id BIGINT UNSIGNED NOT NULL COMMENT '通知批次',
elder_id BIGINT UNSIGNED NOT NULL COMMENT '老人',
owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '当前经办人',
owner_at_issue BIGINT UNSIGNED NOT NULL COMMENT '激活时经办人 统计不随移交改变',
issued_at DATETIME(3) NULL COMMENT '激活纳入统计 UTC',
service_due_date DATE NULL COMMENT '预约或服务日期快照',
receipt_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING CONFIRMED UNREACHABLE REFUSED',
workflow_status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN FOLLOWING CLOSED CANCELLED',
first_confirmed_at DATETIME(3) NULL COMMENT '首次接收确认 UTC',
last_attempt_at DATETIME(3) NULL COMMENT '最近触达 UTC',
closed_at DATETIME(3) NULL COMMENT '终结 UTC',
closed_reason VARCHAR(32) NULL COMMENT '终结原因',
automation_key VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规则周期老人去重键',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_recipient_notice_elder (notice_id,elder_id), UNIQUE KEY uk_recipient_scope (id,community_id),
UNIQUE KEY uk_recipient_automation (automation_key),
KEY idx_recipient_owner (community_id,owner_user_id,workflow_status,id),
KEY idx_recipient_issue (community_id,issued_at,owner_at_issue,id),
CONSTRAINT fk_rec_community FOREIGN KEY (community_id) REFERENCES community(id),
CONSTRAINT fk_rec_notice_scope FOREIGN KEY (notice_id,community_id) REFERENCES notice(id,community_id),
CONSTRAINT fk_rec_elder_scope FOREIGN KEY (elder_id,community_id) REFERENCES elder_profile(id,community_id),
CONSTRAINT fk_rec_owner_scope FOREIGN KEY (owner_user_id,community_id) REFERENCES sys_user(id,community_id),
CONSTRAINT fk_rec_issue_owner_scope FOREIGN KEY (owner_at_issue,community_id) REFERENCES sys_user(id,community_id),
CHECK (receipt_status IN ('PENDING','CONFIRMED','UNREACHABLE','REFUSED')),
CHECK (workflow_status IN ('OPEN','FOLLOWING','CLOSED','CANCELLED')),
CHECK (workflow_status NOT IN ('CLOSED','CANCELLED') OR (closed_at IS NOT NULL AND closed_reason IS NOT NULL)),
CHECK (receipt_status <> 'CONFIRMED' OR first_confirmed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每位老人对应通知对象 与通知批次分离';


-- 触达记录 短信技术回执不等于人工确认
CREATE TABLE delivery_attempt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
recipient_id BIGINT UNSIGNED NOT NULL COMMENT '通知接收对象',
channel VARCHAR(24) NOT NULL COMMENT 'HOME_VISIT FAMILY_RELAY BULLETIN SMS',
attempted_at DATETIME(3) NOT NULL COMMENT '触达 UTC',
result VARCHAR(24) NOT NULL COMMENT 'POSTED SENT DELIVERED CONFIRMED UNREACHABLE REFUSED FAILED',
unreachable_reason VARCHAR(32) NULL COMMENT 'NO_ONE_HOME NO_ANSWER OTHER',
reason_text VARCHAR(300) NULL COMMENT '客观原因',
feedback_quote VARCHAR(500) NULL COMMENT '脱敏后的客观反馈原话',
family_name_masked VARCHAR(40) NULL COMMENT '家属脱敏称谓',
family_relation VARCHAR(32) NULL COMMENT '家属关系',
relay_confirmed TINYINT NULL COMMENT '家属是否明确确认转告',
provider_request_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '外部请求幂等号',
external_contact_token VARBINARY(512) NULL COMMENT '外部备案平台令牌密文 非手机号',
idempotency_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求幂等键',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_attempt_request (recipient_id,idempotency_key), UNIQUE KEY uk_attempt_provider (provider_request_id),
KEY idx_attempt_time (recipient_id,attempted_at,id),
CONSTRAINT fk_da_recipient FOREIGN KEY (recipient_id) REFERENCES notice_recipient(id),
CHECK (channel IN ('HOME_VISIT','FAMILY_RELAY','BULLETIN','SMS')),
CHECK (result IN ('POSTED','SENT','DELIVERED','CONFIRMED','UNREACHABLE','REFUSED','FAILED')),
CHECK (relay_confirmed IS NULL OR relay_confirmed IN (0,1)),
CHECK (result <> 'UNREACHABLE' OR unreachable_reason IS NOT NULL),
CHECK (unreachable_reason IS NULL OR unreachable_reason IN ('NO_ONE_HOME','NO_ANSWER','OTHER')),
CHECK (result <> 'REFUSED' OR reason_text IS NOT NULL),
CHECK (unreachable_reason IS NULL OR unreachable_reason <> 'OTHER' OR reason_text IS NOT NULL),
CHECK (channel <> 'FAMILY_RELAY' OR (family_name_masked IS NOT NULL AND family_relation IS NOT NULL)),
CHECK (channel <> 'FAMILY_RELAY' OR result <> 'CONFIRMED' OR (relay_confirmed IS NOT NULL AND relay_confirmed=1)),
CHECK (channel <> 'BULLETIN' OR result IN ('POSTED','FAILED')),
CHECK (channel <> 'SMS' OR result IN ('SENT','DELIVERED','FAILED')),
CHECK (channel <> 'SMS' OR (provider_request_id IS NOT NULL AND external_contact_token IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='触达记录 短信技术回执不等于人工确认';


-- 私有文件元数据 非AI凭证和报表共用
CREATE TABLE file_object (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
purpose VARCHAR(24) NOT NULL COMMENT 'EVIDENCE REPORT',
storage_key VARCHAR(300) NOT NULL COMMENT '私有存储键 非公开链接',
safe_name VARCHAR(160) NOT NULL COMMENT '脱敏安全文件名',
mime_type VARCHAR(100) NOT NULL COMMENT '真实MIME',
size_bytes BIGINT UNSIGNED NOT NULL COMMENT '字节数',
sha256 BINARY(32) NOT NULL COMMENT '文件内容SHA256',
scan_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING PASSED REJECTED',
redaction_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING PASSED REJECTED',
status VARCHAR(16) NOT NULL DEFAULT 'TEMP' COMMENT 'TEMP ACTIVE ARCHIVED',
expires_at DATETIME(3) NULL COMMENT '过期 UTC',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_file_storage (storage_key), KEY idx_file_scope (community_id,purpose,status,created_at),
CONSTRAINT fk_file_community FOREIGN KEY (community_id) REFERENCES community(id),
CHECK (purpose IN ('EVIDENCE','REPORT')), CHECK (scan_status IN ('PENDING','PASSED','REJECTED')),
CHECK (redaction_status IN ('PENDING','PASSED','REJECTED')), CHECK (status IN ('TEMP','ACTIVE','ARCHIVED')),
CHECK (status <> 'ACTIVE' OR (scan_status='PASSED' AND redaction_status='PASSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私有文件元数据 非AI凭证和报表共用';


-- 触达凭证关联 上门签字及公告照片
CREATE TABLE delivery_attachment (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
delivery_attempt_id BIGINT UNSIGNED NOT NULL COMMENT '触达',
file_id BIGINT UNSIGNED NOT NULL COMMENT '私有脱敏文件',
evidence_type VARCHAR(32) NOT NULL COMMENT 'SIGN_CONFIRM BULLETIN_PHOTO OTHER_ALLOWED',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
UNIQUE KEY uk_delivery_file (delivery_attempt_id,file_id),
CONSTRAINT fk_datt_attempt FOREIGN KEY (delivery_attempt_id) REFERENCES delivery_attempt(id),
CONSTRAINT fk_datt_file FOREIGN KEY (file_id) REFERENCES file_object(id),
CHECK (evidence_type IN ('SIGN_CONFIRM','BULLETIN_PHOTO','OTHER_ALLOWED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='触达凭证关联 上门签字及公告照片';


-- 通知接收及闭环状态历史
CREATE TABLE notice_recipient_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
recipient_id BIGINT UNSIGNED NOT NULL COMMENT '通知对象',
event_type VARCHAR(32) NOT NULL COMMENT 'ISSUED RECEIPT_CHANGED CLOSED CANCELLED REOPENED CORRECTED TRANSFERRED',
from_receipt_status VARCHAR(24) NULL COMMENT '原接收状态',
to_receipt_status VARCHAR(24) NOT NULL COMMENT '新接收状态',
from_workflow_status VARCHAR(16) NULL COMMENT '原工作流状态',
to_workflow_status VARCHAR(16) NOT NULL COMMENT '新工作流状态',
delivery_attempt_id BIGINT UNSIGNED NULL COMMENT '来源触达',
effective_at DATETIME(3) NOT NULL COMMENT '业务生效 UTC',
owner_snapshot_id BIGINT UNSIGNED NOT NULL COMMENT '当时负责人',
reason VARCHAR(300) NULL COMMENT '更正取消等原因',
request_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件请求去重键',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
UNIQUE KEY uk_rec_event_request (recipient_id,request_key), KEY idx_rec_event_time (recipient_id,effective_at,id),
CONSTRAINT fk_re_recipient FOREIGN KEY (recipient_id) REFERENCES notice_recipient(id),
CONSTRAINT fk_re_attempt FOREIGN KEY (delivery_attempt_id) REFERENCES delivery_attempt(id),
CONSTRAINT fk_re_owner FOREIGN KEY (owner_snapshot_id) REFERENCES sys_user(id),
CHECK (event_type IN ('ISSUED','RECEIPT_CHANGED','CLOSED','CANCELLED','REOPENED','CORRECTED','TRANSFERRED')),
CHECK (to_receipt_status IN ('PENDING','CONFIRMED','UNREACHABLE','REFUSED')),
CHECK (to_workflow_status IN ('OPEN','FOLLOWING','CLOSED','CANCELLED')),
CHECK (from_receipt_status IS NULL OR from_receipt_status IN ('PENDING','CONFIRMED','UNREACHABLE','REFUSED')),
CHECK (from_workflow_status IS NULL OR from_workflow_status IN ('OPEN','FOLLOWING','CLOSED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知接收及闭环状态历史';


-- 跟进任务 未完成同对象同类型唯一
CREATE TABLE followup_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
recipient_id BIGINT UNSIGNED NOT NULL COMMENT '通知对象',
task_type VARCHAR(32) NOT NULL COMMENT 'RECONTACT VERIFY_APPOINTMENT PRE_VACCINE_REMINDER',
assignee_id BIGINT UNSIGNED NOT NULL COMMENT '当前经办人',
source_attempt_id BIGINT UNSIGNED NULL COMMENT '来源触达',
status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING PROCESSING COMPLETED CANCELLED',
due_at DATETIME(3) NOT NULL COMMENT '截止 UTC',
retry_no INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重试序号',
rule_snapshot_json JSON NOT NULL COMMENT '当次任务规则',
open_dedupe_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '未结束对象类型键 终结时置空',
completed_at DATETIME(3) NULL COMMENT '完成 UTC',
cancel_reason VARCHAR(300) NULL COMMENT '取消原因',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_task_open (open_dedupe_key), KEY idx_task_due (community_id,assignee_id,status,due_at,id),
CONSTRAINT fk_ft_community FOREIGN KEY (community_id) REFERENCES community(id),
CONSTRAINT fk_ft_rec_scope FOREIGN KEY (recipient_id,community_id) REFERENCES notice_recipient(id,community_id),
CONSTRAINT fk_ft_assignee_scope FOREIGN KEY (assignee_id,community_id) REFERENCES sys_user(id,community_id),
CONSTRAINT fk_ft_attempt FOREIGN KEY (source_attempt_id) REFERENCES delivery_attempt(id),
CHECK (task_type IN ('RECONTACT','VERIFY_APPOINTMENT','PRE_VACCINE_REMINDER')),
CHECK (status IN ('PENDING','PROCESSING','COMPLETED','CANCELLED')),
CHECK ((status IN ('PENDING','PROCESSING') AND open_dedupe_key IS NOT NULL) OR (status IN ('COMPLETED','CANCELLED') AND open_dedupe_key IS NULL)),
CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL), CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跟进任务 未完成同对象同类型唯一';


-- 跟进执行记录
CREATE TABLE followup_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
task_id BIGINT UNSIGNED NOT NULL COMMENT '跟进任务',
performed_at DATETIME(3) NOT NULL COMMENT '执行 UTC',
outcome VARCHAR(32) NOT NULL COMMENT 'RESOLVED RETRY_REQUIRED REFUSED NO_LONGER_APPLICABLE',
result_text VARCHAR(1000) NOT NULL COMMENT '脱敏执行结果',
delivery_attempt_id BIGINT UNSIGNED NULL COMMENT '本次触达 无触达可空',
next_due_at DATETIME(3) NULL COMMENT '下次期限 UTC',
idempotency_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求去重',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
UNIQUE KEY uk_followup_record_request (task_id,idempotency_key), KEY idx_followup_time (task_id,performed_at,id),
CONSTRAINT fk_fr_task FOREIGN KEY (task_id) REFERENCES followup_task(id),
CONSTRAINT fk_fr_attempt FOREIGN KEY (delivery_attempt_id) REFERENCES delivery_attempt(id),
CHECK (outcome IN ('RESOLVED','RETRY_REQUIRED','REFUSED','NO_LONGER_APPLICABLE')),
CHECK (outcome <> 'RETRY_REQUIRED' OR next_due_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跟进执行记录';


-- 满意度反馈版本 历史不覆盖
CREATE TABLE service_feedback (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
recipient_id BIGINT UNSIGNED NOT NULL COMMENT '通知对象',
revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '反馈版本',
rating VARCHAR(24) NOT NULL COMMENT 'SATISFIED NEUTRAL DISSATISFIED',
respondent_type VARCHAR(16) NOT NULL COMMENT 'ELDER FAMILY',
feedback_text VARCHAR(500) NULL COMMENT '脱敏反馈',
collected_at DATETIME(3) NOT NULL COMMENT '采集 UTC',
effective_at DATETIME(3) NOT NULL COMMENT '反馈版本生效 UTC',
is_valid TINYINT NOT NULL DEFAULT 1 COMMENT '0撤销 1有效',
invalid_reason VARCHAR(200) NULL COMMENT '撤销原因',
supersedes_feedback_id BIGINT UNSIGNED NULL COMMENT '被取代反馈',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
UNIQUE KEY uk_feedback_revision (recipient_id,revision_no), KEY idx_feedback_effective (recipient_id,effective_at,revision_no),
CONSTRAINT fk_sf_recipient FOREIGN KEY (recipient_id) REFERENCES notice_recipient(id),
CONSTRAINT fk_sf_supersedes FOREIGN KEY (supersedes_feedback_id) REFERENCES service_feedback(id),
CHECK (revision_no >= 1), CHECK (rating IN ('SATISFIED','NEUTRAL','DISSATISFIED')),
CHECK (respondent_type IN ('ELDER','FAMILY')), CHECK (is_valid IN (0,1)),
CHECK (is_valid <> 0 OR invalid_reason IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='满意度反馈版本 历史不覆盖';


-- 报表冻结及导出作业
CREATE TABLE report_job (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NOT NULL COMMENT '社区',
report_type VARCHAR(32) NOT NULL COMMENT 'MONTHLY_SERVICE',
period_start DATE NOT NULL COMMENT '上海时区起始日期',
period_end_exclusive DATE NOT NULL COMMENT '上海时区结束日期不包含',
scope_owner_id BIGINT UNSIGNED NULL COMMENT '为空表示获授权社区汇总',
filter_json JSON NOT NULL COMMENT '白名单筛选参数',
metric_version VARCHAR(32) NOT NULL COMMENT '统计口径版本',
revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '修订版本',
snapshot_at DATETIME(3) NOT NULL COMMENT '快照 UTC',
status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING PROCESSING COMPLETED FAILED CANCELLED',
requested_by BIGINT UNSIGNED NOT NULL COMMENT '申请人',
format VARCHAR(16) NOT NULL COMMENT 'XLSX DOCX',
output_file_id BIGINT UNSIGNED NULL COMMENT '成功生成的报表文件',
error_summary VARCHAR(500) NULL COMMENT '脱敏错误摘要',
expires_at DATETIME(3) NOT NULL COMMENT '到期 UTC',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
KEY idx_report_requester (community_id,requested_by,created_at,id),
CONSTRAINT fk_rj_community FOREIGN KEY (community_id) REFERENCES community(id),
CONSTRAINT fk_rj_scope FOREIGN KEY (scope_owner_id,community_id) REFERENCES sys_user(id,community_id),
CONSTRAINT fk_rj_requester FOREIGN KEY (requested_by,community_id) REFERENCES sys_user(id,community_id),
CONSTRAINT fk_rj_file FOREIGN KEY (output_file_id) REFERENCES file_object(id),
CHECK (period_start < period_end_exclusive), CHECK (revision_no >= 1),
CHECK (format IN ('XLSX','DOCX')), CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
CHECK (status <> 'COMPLETED' OR output_file_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表冻结及导出作业';


-- 报表指标快照 保留分子分母
CREATE TABLE report_metric (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
report_job_id BIGINT UNSIGNED NOT NULL COMMENT '报表作业',
metric_code VARCHAR(64) NOT NULL COMMENT '指标代码',
dimension_key VARCHAR(180) NOT NULL COMMENT '标准化维度键',
dimension_json JSON NOT NULL COMMENT '统计维度',
numerator BIGINT UNSIGNED NULL COMMENT '分子',
denominator BIGINT UNSIGNED NULL COMMENT '分母',
value_decimal DECIMAL(18,6) NULL COMMENT '比例0到1或指标值 非百分数字符串',
null_reason VARCHAR(80) NULL COMMENT '无值原因',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
UNIQUE KEY uk_report_metric (report_job_id,metric_code,dimension_key),
CONSTRAINT fk_rm_job FOREIGN KEY (report_job_id) REFERENCES report_job(id),
CHECK (denominator IS NULL OR denominator <> 0 OR value_decimal IS NULL),
CHECK (value_decimal IS NOT NULL OR null_reason IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表指标快照 保留分子分母';


-- 操作审计 仅保存脱敏白名单摘要
CREATE TABLE audit_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NULL COMMENT '社区',
actor_user_id BIGINT UNSIGNED NULL COMMENT '人工操作者',
system_actor VARCHAR(64) NULL COMMENT '机器或初始化来源',
action VARCHAR(80) NOT NULL COMMENT '动作',
target_type VARCHAR(64) NOT NULL COMMENT '资源类型',
target_id VARCHAR(64) NULL COMMENT '资源ID',
trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '链路ID',
result VARCHAR(16) NOT NULL COMMENT 'SUCCESS FAILED DENIED',
change_summary_json JSON NULL COMMENT '脱敏字段变化 禁止原始请求',
occurred_at DATETIME(3) NOT NULL COMMENT '动作发生 UTC',
client_ip_cipher VARBINARY(256) NULL COMMENT '必要时加密IP',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
PRIMARY KEY (id),
KEY idx_audit_community_time (community_id,occurred_at,id),
KEY idx_audit_actor_time (actor_user_id,occurred_at,id), KEY idx_audit_trace (trace_id),
CONSTRAINT fk_audit_community FOREIGN KEY (community_id) REFERENCES community(id),
CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id),
CHECK (result IN ('SUCCESS','FAILED','DENIED')), CHECK (actor_user_id IS NOT NULL OR system_actor IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计 仅保存脱敏白名单摘要';


-- 持久异步作业及租约 不含AI任务类型
CREATE TABLE job_run (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
community_id BIGINT UNSIGNED NULL COMMENT '社区',
job_type VARCHAR(32) NOT NULL COMMENT 'NOTICE_GENERATION REPORT_EXPORT SMS_DISPATCH',
business_key VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务幂等键',
status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING PROCESSING COMPLETED FAILED CANCELLED',
payload_json JSON NOT NULL COMMENT '仅内部ID和受控参数',
cursor_value VARCHAR(200) NULL COMMENT '分页游标',
retry_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已重试次数',
max_retries INT UNSIGNED NOT NULL DEFAULT 3 COMMENT '最大重试',
next_run_at DATETIME(3) NOT NULL COMMENT '下次执行 UTC',
lease_owner VARCHAR(100) NULL COMMENT '租约持有进程',
lease_until DATETIME(3) NULL COMMENT '租约截至 UTC',
error_summary VARCHAR(500) NULL COMMENT '脱敏失败摘要',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_job_business (job_type,business_key), KEY idx_job_claim (status,next_run_at,lease_until,id),
CONSTRAINT fk_job_community FOREIGN KEY (community_id) REFERENCES community(id),
CHECK (job_type IN ('NOTICE_GENERATION','REPORT_EXPORT','SMS_DISPATCH')),
CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
CHECK ((lease_owner IS NULL AND lease_until IS NULL) OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持久异步作业及租约 不含AI任务类型';


-- 事务外发事件 允许至少一次投递
CREATE TABLE outbox_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件UUID',
aggregate_type VARCHAR(64) NOT NULL COMMENT '聚合类型',
aggregate_id BIGINT UNSIGNED NOT NULL COMMENT '聚合ID 非多态物理外键',
event_type VARCHAR(80) NOT NULL COMMENT '事件类型',
schema_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '载荷结构版本',
payload_json JSON NOT NULL COMMENT '仅必要内部ID及参数',
status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING PROCESSING PUBLISHED FAILED',
retry_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重试次数',
next_retry_at DATETIME(3) NOT NULL COMMENT '下次尝试 UTC',
published_at DATETIME(3) NULL COMMENT '成功投递 UTC',
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
created_by BIGINT UNSIGNED NULL COMMENT '创建用户 机器及首次初始化可为空',
updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
updated_by BIGINT UNSIGNED NULL COMMENT '更新用户',
version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本 应用更新时递增',
PRIMARY KEY (id),
UNIQUE KEY uk_outbox_event (event_id), KEY idx_outbox_dispatch (status,next_retry_at,id),
CHECK (schema_version >= 1), CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事务外发事件 允许至少一次投递';


-- 通用审计用户外键在所有基础表创建后补齐，避免建表依赖循环。
ALTER TABLE community ADD CONSTRAINT fk_community_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE community ADD CONSTRAINT fk_community_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE grid ADD CONSTRAINT fk_grid_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE grid ADD CONSTRAINT fk_grid_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE sys_user ADD CONSTRAINT fk_sys_user_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE sys_user ADD CONSTRAINT fk_sys_user_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE sys_role ADD CONSTRAINT fk_sys_role_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE sys_role ADD CONSTRAINT fk_sys_role_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE sys_permission ADD CONSTRAINT fk_sys_permission_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE sys_permission ADD CONSTRAINT fk_sys_permission_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE sys_user_role ADD CONSTRAINT fk_sys_user_role_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE sys_role_permission ADD CONSTRAINT fk_sys_role_permission_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE elder_no_sequence ADD CONSTRAINT fk_elder_no_sequence_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE elder_no_sequence ADD CONSTRAINT fk_elder_no_sequence_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE elder_profile ADD CONSTRAINT fk_elder_profile_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE elder_profile ADD CONSTRAINT fk_elder_profile_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE elder_assignment_history ADD CONSTRAINT fk_elder_assignment_history_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE service_need ADD CONSTRAINT fk_service_need_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE service_need ADD CONSTRAINT fk_service_need_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE elder_service_need ADD CONSTRAINT fk_elder_service_need_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE elder_service_need ADD CONSTRAINT fk_elder_service_need_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE vaccine_product ADD CONSTRAINT fk_vaccine_product_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE vaccine_product ADD CONSTRAINT fk_vaccine_product_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE vaccine_campaign ADD CONSTRAINT fk_vaccine_campaign_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE vaccine_campaign ADD CONSTRAINT fk_vaccine_campaign_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE elder_vaccine_status ADD CONSTRAINT fk_elder_vaccine_status_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE elder_vaccine_status ADD CONSTRAINT fk_elder_vaccine_status_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE vaccination_record ADD CONSTRAINT fk_vaccination_record_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE notice_rule ADD CONSTRAINT fk_notice_rule_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE notice_rule ADD CONSTRAINT fk_notice_rule_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE notice ADD CONSTRAINT fk_notice_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE notice ADD CONSTRAINT fk_notice_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE notice_recipient ADD CONSTRAINT fk_notice_recipient_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE notice_recipient ADD CONSTRAINT fk_notice_recipient_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE delivery_attempt ADD CONSTRAINT fk_delivery_attempt_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE delivery_attempt ADD CONSTRAINT fk_delivery_attempt_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE file_object ADD CONSTRAINT fk_file_object_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE file_object ADD CONSTRAINT fk_file_object_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE delivery_attachment ADD CONSTRAINT fk_delivery_attachment_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE notice_recipient_event ADD CONSTRAINT fk_notice_recipient_event_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE followup_task ADD CONSTRAINT fk_followup_task_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE followup_task ADD CONSTRAINT fk_followup_task_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE followup_record ADD CONSTRAINT fk_followup_record_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE service_feedback ADD CONSTRAINT fk_service_feedback_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE report_job ADD CONSTRAINT fk_report_job_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE report_job ADD CONSTRAINT fk_report_job_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE report_metric ADD CONSTRAINT fk_report_metric_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE audit_log ADD CONSTRAINT fk_audit_log_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE job_run ADD CONSTRAINT fk_job_run_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE job_run ADD CONSTRAINT fk_job_run_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);
ALTER TABLE outbox_event ADD CONSTRAINT fk_outbox_event_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id);
ALTER TABLE outbox_event ADD CONSTRAINT fk_outbox_event_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id);

-- 以下均为虚构演示数据。业务发生时刻采用固定UTC日期，避免导入日期改变样例。
-- 不伪造真实短信、签字凭证或导出成功记录。文件/附件/报表/队列无业务数据时留空。
START TRANSACTION;

INSERT INTO community (`id`,`code`,`name`,`status`) VALUES
(1,'DEMO-SH-001','示例社区','ACTIVE');
INSERT INTO grid (`id`,`community_id`,`code`,`name`,`status`) VALUES
(1,1,'GRID-01','第一网格','ACTIVE');
INSERT INTO sys_user (`id`,`community_id`,`username`,`password_hash`,`display_name`,`status`) VALUES
(1,1,'admin_demo','!DISABLED_DEMO_ACCOUNT_NO_LOGIN','演示管理员','DISABLED'),
(2,1,'grid_demo','!DISABLED_DEMO_ACCOUNT_NO_LOGIN','演示网格员','DISABLED'),
(3,1,'stats_demo','!DISABLED_DEMO_ACCOUNT_NO_LOGIN','演示统计员','DISABLED');
INSERT INTO sys_role (`id`,`code`,`name`,`status`) VALUES
(1,'ADMIN','管理员','ACTIVE'),
(2,'GRID_WORKER','网格员','ACTIVE'),
(3,'STATISTICIAN','统计员','ACTIVE');
INSERT INTO sys_permission (`id`,`code`,`name`,`resource_type`) VALUES
(1,'system:user:manage','用户管理','ACTION'),
(2,'system:role:manage','角色权限管理','ACTION'),
(3,'system:config:manage','系统参数管理','ACTION'),
(4,'elder:transfer','老人归属移交','ACTION'),
(5,'audit:read','查看审计','ACTION'),
(6,'elder:read','读取本人老人档案','ACTION'),
(7,'elder:create','登记老人档案','ACTION'),
(8,'elder:update','维护本人老人档案','ACTION'),
(9,'vaccine:verify','资格与接种核验','ACTION'),
(10,'notice:read','读取本人通知','ACTION'),
(11,'notice:create','创建本人通知','ACTION'),
(12,'notice:receive','登记触达及接收','ACTION'),
(13,'followup:read','读取本人跟进','ACTION'),
(14,'followup:update','记录本人跟进','ACTION'),
(15,'feedback:create','采集本人服务反馈','ACTION'),
(16,'report:read:own','读取本人汇总','ACTION'),
(17,'report:export:own','导出本人汇总','ACTION'),
(18,'report:read:community','读取社区汇总','ACTION'),
(19,'report:export:community','导出社区汇总','ACTION'),
(20,'file:upload','上传业务附件','ACTION'),
(21,'file:read','按业务权限读取附件','ACTION'),
(22,'notice:rule:manage','维护通知规则','ACTION');
INSERT INTO sys_user_role (`id`,`user_id`,`role_id`) VALUES
(1,1,1),
(2,2,2),
(3,3,3);
INSERT INTO sys_role_permission (`id`,`role_id`,`permission_id`) VALUES
(1,1,1),
(2,1,2),
(3,1,3),
(4,1,4),
(5,1,5),
(6,1,22),
(7,2,6),
(8,2,7),
(9,2,8),
(10,2,9),
(11,2,10),
(12,2,11),
(13,2,12),
(14,2,13),
(15,2,14),
(16,2,15),
(17,2,16),
(18,2,17),
(19,2,20),
(20,2,21),
(21,3,18),
(22,3,19);
INSERT INTO elder_no_sequence (`id`,`community_id`,`register_year`,`next_value`) VALUES
(1,1,2026,4);
INSERT INTO elder_profile (`id`,`community_id`,`grid_id`,`owner_user_id`,`elder_no_cipher`,`elder_no_hash`,`key_version`,`register_year`,`sex`,`birth_year`,`birth_month`,`registered_by`,`info_updated_at`,`status`,`remark`,`created_at`,`created_by`) VALUES
(1,1,1,2,X'73e4afafac985ea3e7710116382c29ec59b05d10e42e6b37c5b2fde697b1f89f5f83c46a88d33acf2d',X'0edabda4d52f21d6ea4fcb30d8622d62b8305da4975f547fb00b49d5b31729c8',1,2026,'MALE',1948,10,2,'2026-10-01 01:00:00.000','ACTIVE','虚构演示档案A','2026-10-01 01:00:00.000',2),
(2,1,1,2,X'3cbfd7064d03e23ad0eddd075689d94f718a643a6554cf33feeb13f1fb8270c18f59a690eb20bef11a',X'6e40ccdfaf0869ad289b7ca97f27dcc854dc081ff3f31c5f9d440064078e7b8d',1,2026,'FEMALE',1955,11,2,'2026-10-01 01:00:00.000','ACTIVE','虚构演示档案B','2026-10-01 01:00:00.000',2),
(3,1,1,2,X'7c40351725bb9e7ee712664c69f3fc4e9eb03f37b245480365500f8368f5ecb02d7acbd72abf5cb6e1',X'db5ae112cde2f36a28eb5c237f78e018355a0d2599fa4f66784d4fc065dbda93',1,2026,'FEMALE',1960,3,2,'2026-10-01 01:00:00.000','ACTIVE','虚构演示档案C','2026-10-01 01:00:00.000',2);
INSERT INTO elder_assignment_history (`id`,`elder_id`,`old_grid_id`,`new_grid_id`,`old_owner_id`,`new_owner_id`,`effective_at`,`reason`,`created_at`,`created_by`) VALUES
(1,1,NULL,1,NULL,2,'2026-10-01 01:00:00.000','虚构演示首次登记','2026-10-01 01:00:00.000',2),
(2,2,NULL,1,NULL,2,'2026-10-01 01:00:00.000','虚构演示首次登记','2026-10-01 01:00:00.000',2),
(3,3,NULL,1,NULL,2,'2026-10-01 01:00:00.000','虚构演示首次登记','2026-10-01 01:00:00.000',2);
INSERT INTO service_need (`id`,`code`,`name`,`status`) VALUES
(1,'BIRTHDAY_CARE','生日关怀','ACTIVE'),
(2,'VACCINE_REMINDER','疫苗接种提醒','ACTIVE'),
(3,'HEALTH_LECTURE','健康讲座','ACTIVE');
INSERT INTO elder_service_need (`id`,`elder_id`,`need_id`,`status`,`created_by`) VALUES
(1,1,1,'ACTIVE',2),
(2,1,2,'ACTIVE',2),
(3,2,2,'ACTIVE',2),
(4,3,3,'ACTIVE',2);
INSERT INTO vaccine_product (`id`,`code`,`name`,`status`) VALUES
(1,'INFLUENZA','流感疫苗','ACTIVE'),
(2,'COVID19','新冠疫苗','ACTIVE');
INSERT INTO vaccine_campaign (`id`,`community_id`,`vaccine_id`,`cycle_key`,`dose_stage`,`name`,`start_date`,`end_date`,`eligibility_rule_ref`,`status`,`created_by`) VALUES
(1,1,1,'2026','ANNUAL','2026年度流感疫苗服务','2026-01-01','2026-12-31','DEMO-MANUAL-ONLY','ACTIVE',1);
INSERT INTO elder_vaccine_status (`id`,`elder_id`,`campaign_id`,`eligibility`,`eligibility_checked_by`,`eligibility_checked_at`,`vaccination_status`,`appointment_date`,`status_effective_at`,`source`,`created_by`) VALUES
(1,1,1,'ELIGIBLE',2,'2026-10-10 01:00:00.000','COMPLETED',NULL,'2026-10-12 02:00:00.000','MANUAL',2),
(2,2,1,'ELIGIBLE',2,'2026-10-10 01:00:00.000','NOT_VACCINATED','2026-10-20','2026-10-10 01:00:00.000','MANUAL',2),
(3,3,1,'UNKNOWN',NULL,NULL,'UNKNOWN',NULL,'2026-10-10 01:00:00.000','MANUAL',2);
INSERT INTO vaccination_record (`id`,`elder_vaccine_status_id`,`action`,`vaccinated_on`,`dose_no`,`resulting_status`,`eligibility_snapshot`,`effective_at`,`verified_by`,`source`,`reason`,`created_by`) VALUES
(1,1,'ASSESSMENT',NULL,NULL,'NOT_VACCINATED','ELIGIBLE','2026-10-10 01:00:00.000',2,'MANUAL','虚构资格核验',2),
(2,1,'VACCINATION','2026-10-12',1,'COMPLETED','ELIGIBLE','2026-10-12 02:00:00.000',2,'MANUAL','虚构接种核验记录',2),
(3,2,'ASSESSMENT',NULL,NULL,'NOT_VACCINATED','ELIGIBLE','2026-10-10 01:00:00.000',2,'MANUAL','虚构资格核验 已预约尚未接种',2),
(4,3,'ASSESSMENT',NULL,NULL,'UNKNOWN','UNKNOWN','2026-10-10 01:00:00.000',2,'MANUAL','虚构资格待核实 不进入接种率分母',2);
INSERT INTO notice_rule (`id`,`community_id`,`code`,`name`,`notice_type`,`trigger_kind`,`rule_version`,`config_json`,`enabled`,`created_by`) VALUES
(1,1,'BIRTH_MONTH_CARE','生日月份关怀','BIRTHDAY','BIRTH_MONTH',1,'{"prepare_days_before_next_month":7,"need_code":"BIRTHDAY_CARE"}',0,1);
INSERT INTO notice (`id`,`community_id`,`notice_no`,`type`,`title`,`content`,`source`,`period_key`,`campaign_id`,`active_at`,`status`,`created_by`) VALUES
(1,1,'NT-202610-001','BIRTHDAY','十月生日关怀','虚构演示通知：开展生日月份关怀服务。','MANUAL','2026-10',NULL,'2026-10-13 01:00:00.000','ACTIVE',2),
(2,1,'NT-202610-002','VACCINE','流感疫苗预约提醒','虚构演示通知：提醒已核验资格的对象确认预约安排。','MANUAL','2026-10',1,'2026-10-13 01:00:00.000','ACTIVE',2);
INSERT INTO notice_recipient (`id`,`community_id`,`notice_id`,`elder_id`,`owner_user_id`,`owner_at_issue`,`issued_at`,`service_due_date`,`receipt_status`,`workflow_status`,`first_confirmed_at`,`last_attempt_at`,`closed_at`,`closed_reason`,`created_by`) VALUES
(1,1,1,1,2,2,'2026-10-13 01:00:00.000',NULL,'CONFIRMED','CLOSED','2026-10-14 02:00:00.000','2026-10-14 02:00:00.000','2026-10-14 02:00:00.000','CONFIRMED',2),
(2,1,2,2,2,2,'2026-10-13 01:00:00.000','2026-10-20','UNREACHABLE','FOLLOWING',NULL,'2026-10-14 03:00:00.000',NULL,NULL,2);
INSERT INTO delivery_attempt (`id`,`recipient_id`,`channel`,`attempted_at`,`result`,`unreachable_reason`,`reason_text`,`feedback_quote`,`family_name_masked`,`family_relation`,`relay_confirmed`,`idempotency_key`,`created_by`) VALUES
(1,1,'FAMILY_RELAY','2026-10-13 02:00:00.000','UNREACHABLE','OTHER','虚构示例 家属暂未能确认转告','暂时无法确认','某某','子女',0,'demo-attempt-1',2),
(2,1,'FAMILY_RELAY','2026-10-14 02:00:00.000','CONFIRMED',NULL,NULL,'已经转告，愿意参加','某某','子女',1,'demo-attempt-2',2),
(3,2,'HOME_VISIT','2026-10-14 03:00:00.000','UNREACHABLE','NO_ONE_HOME','虚构示例 上门无人',NULL,NULL,NULL,NULL,'demo-attempt-3',2);
INSERT INTO notice_recipient_event (`id`,`recipient_id`,`event_type`,`from_receipt_status`,`to_receipt_status`,`from_workflow_status`,`to_workflow_status`,`delivery_attempt_id`,`effective_at`,`owner_snapshot_id`,`reason`,`request_key`,`created_by`) VALUES
(1,1,'ISSUED',NULL,'PENDING',NULL,'OPEN',NULL,'2026-10-13 01:00:00.000',2,'虚构通知激活','demo-issue-1',2),
(2,2,'ISSUED',NULL,'PENDING',NULL,'OPEN',NULL,'2026-10-13 01:00:00.000',2,'虚构通知激活','demo-issue-2',2),
(3,1,'RECEIPT_CHANGED','PENDING','UNREACHABLE','OPEN','FOLLOWING',1,'2026-10-13 02:00:00.000',2,'虚构触达结果','demo-event-attempt-1',2),
(4,1,'RECEIPT_CHANGED','UNREACHABLE','CONFIRMED','FOLLOWING','CLOSED',2,'2026-10-14 02:00:00.000',2,'虚构触达结果','demo-event-attempt-2',2),
(5,2,'RECEIPT_CHANGED','PENDING','UNREACHABLE','OPEN','FOLLOWING',3,'2026-10-14 03:00:00.000',2,'虚构触达结果','demo-event-attempt-3',2);
INSERT INTO followup_task (`id`,`community_id`,`recipient_id`,`task_type`,`assignee_id`,`source_attempt_id`,`status`,`due_at`,`retry_no`,`rule_snapshot_json`,`open_dedupe_key`,`completed_at`,`created_by`) VALUES
(1,1,1,'RECONTACT',2,1,'COMPLETED','2026-10-14 09:00:00.000',1,'{"retry_after_days":1,"max_retries":3}',NULL,'2026-10-14 02:00:00.000',2),
(2,1,2,'RECONTACT',2,3,'PENDING','2026-10-15 09:00:00.000',1,'{"retry_after_days":1,"max_retries":3}','recipient:2:RECONTACT',NULL,2);
INSERT INTO followup_record (`id`,`task_id`,`performed_at`,`outcome`,`result_text`,`delivery_attempt_id`,`idempotency_key`,`created_by`) VALUES
(1,1,'2026-10-14 02:00:00.000','RESOLVED','虚构示例 家属明确确认已转告，通知闭环。',2,'demo-followup-record-1',2);
INSERT INTO service_feedback (`id`,`recipient_id`,`revision_no`,`rating`,`respondent_type`,`feedback_text`,`collected_at`,`effective_at`,`is_valid`,`created_by`) VALUES
(1,1,1,'SATISFIED','FAMILY','虚构示例 对通知服务满意','2026-10-14 03:00:00.000','2026-10-14 03:00:00.000',1,2);
INSERT INTO audit_log (`id`,`community_id`,`actor_user_id`,`system_actor`,`action`,`target_type`,`target_id`,`trace_id`,`result`,`change_summary_json`,`occurred_at`) VALUES
(1,1,NULL,'SQL_INITIALIZER','DEMO_DATA_INITIALIZED','DATABASE','community_elder_service','demo-initialization-v1','SUCCESS','{"synthetic":true,"ai_module":false}','2026-10-16 01:00:00.000');
COMMIT;

-- 导入完成自检
SELECT DATABASE() AS current_database, VERSION() AS server_version;
SELECT COUNT(*) AS expected_31_tables FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type='BASE TABLE';
SELECT '虚构示例：老人3条，通知2批，接收对象2条，跟进任务2条；无AI表，无真实联系方式' AS import_summary;
