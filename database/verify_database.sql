-- 只读核验，在DBeaver中选择整个脚本执行。
SET NAMES utf8mb4;
SET SESSION time_zone = '+00:00';
USE community_elder_service;

-- 应为31，不含policy和qa表。
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';

SELECT table_name, table_comment
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
ORDER BY table_name;

-- 应为3；显示编号按设计已加密，DBeaver中显示为二进制是正常情况。
SELECT e.id, e.remark, e.sex, e.birth_year, e.birth_month,
       g.name AS grid_name, u.display_name AS owner_name,
       OCTET_LENGTH(e.elder_no_cipher) AS encrypted_number_bytes
FROM elder_profile e
JOIN grid g ON g.id = e.grid_id
JOIN sys_user u ON u.id = e.owner_user_id
ORDER BY e.id;

-- 两个对象：一条已接收且闭环，一条未联系上且待跟进。
SELECT n.notice_no, n.title, r.elder_id, r.receipt_status,
       r.workflow_status, r.closed_reason,
       t.id AS task_id, t.status AS task_status, t.due_at
FROM notice_recipient r
JOIN notice n ON n.id = r.notice_id
LEFT JOIN followup_task t ON t.recipient_id = r.id
ORDER BY r.id, t.id;

-- 演示队列通知覆盖率为50.00%；不是实际社区统计。
SELECT COUNT(*) AS recipients,
       SUM(receipt_status = 'CONFIRMED') AS confirmed,
       ROUND(100.0 * SUM(receipt_status = 'CONFIRMED') / NULLIF(COUNT(*),0),2) AS coverage_percent
FROM notice_recipient
WHERE workflow_status <> 'CANCELLED';

-- 演示疫苗周期分母2、分子1，接种率50.00%；UNKNOWN资格不纳入分母。
SELECT campaign_id,
       SUM(eligibility = 'ELIGIBLE') AS eligible_people,
       SUM(eligibility = 'ELIGIBLE' AND vaccination_status = 'COMPLETED') AS completed_people,
       SUM(eligibility = 'UNKNOWN') AS unknown_eligibility_people,
       ROUND(100.0 * SUM(eligibility = 'ELIGIBLE' AND vaccination_status = 'COMPLETED')
         / NULLIF(SUM(eligibility = 'ELIGIBLE'),0),2) AS vaccination_percent
FROM elder_vaccine_status GROUP BY campaign_id;

-- 应为0行；AI的6张表本次全部排除。
SELECT table_name FROM information_schema.tables
WHERE table_schema=DATABASE()
  AND table_name IN ('policy_document','policy_version','policy_chunk','qa_session','qa_message','qa_citation');

-- 应为0。校验上门已确认记录是否缺少签字凭证。
SELECT COUNT(*) AS invalid_confirmed_home_visits
FROM delivery_attempt d
WHERE d.channel='HOME_VISIT' AND d.result='CONFIRMED'
AND NOT EXISTS (
  SELECT 1 FROM delivery_attachment a
  WHERE a.delivery_attempt_id=d.id AND a.evidence_type='SIGN_CONFIRM'
);

-- 无后台代码前，应用账号均为DISABLED，不能作为DBeaver数据库账号。
SELECT username, display_name, status FROM sys_user ORDER BY id;
