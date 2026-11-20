# 社区老年服务业务微服务接口文档

版本1.6.0，基于实际运行服务导出的OpenAPI生成。含多社区、AI政策问答、业务闭环与短信配置接口。
安全与启动详见[密码与安全检查说明](../../密码与安全检查说明.md)，权限范围详见[多社区权限接口](多社区权限接口.md)。

## 一 调用约定

默认HTTPS服务地址`https://127.0.0.1:8443`，接口前缀`/api/v1`。请求和响应UTF-8，业务JSON请求使用`Content-Type: application/json`。所有接口当前成功状态为200；异步接口通过响应中的作业ID表示排队，不代表已经完成。

使用JSESSIONID Cookie保持会话。登录前先GET`/auth/csrf`，将响应data.token作为`X-XSRF-TOKEN`头用于登录。登录后重新GET一次csrf；每次POST、PUT、PATCH、DELETE均携带Cookie和该头。过期会话返回401。

管理员配置规则、分配社区及老人归属、查看全部社区工作进度，不代办建档、接种核验、通知发布、联系登记、短信、跟进和满意度等业务写入（旧角色权限仍有授权也返回403）。网格员仅操作获授权社区内本人负责档案，统计员仅能查看汇总与报表。X-Community-Id每次由后端验证；指定负责人、移交及统计筛选均按角色和社区核验，不信任客户端传参。越权对象404；缺少功能权限403。

响应BIGINT主键、Long统计数量和total统一为字符串，避免JavaScript精度丢失；version、page、pageSize为整数，比例value为0到1的小数。SQL列名输出转换为camelCase。日期为YYYY-MM-DD，数据库时间为UTC，响应时间为ISO8601；展示请转上海时区。

```json
{"data":{"id":"4","version":0},"traceId":"请求链路UUID"}
```

分页data为`{items,total,page,pageSize}`，page从1开始，pageSize默认为20、最大100。详情和列表不返回passwordHash、编号密文、HMAC摘要或外部联系人令牌。

| 状态 | 含义 |
| --- | --- |
| 400 | JSON、字段格式或校验失败 |
| 401 | 未登录、会话失效、账号停用或登录失败 |
| 403 | 功能权限不足或CSRF无效 |
| 404 | 资源不存在或不在本人范围 |
| 409 | version冲突、唯一键或幂等内容冲突 |
| 422 | 状态、关联或业务条件不满足 |
| 426 | HTTPS强制模式拒绝明文请求 |
| 429 | 当前密码验证暂时锁定 |
| 500 | 未预期服务错误，仅返回脱敏信息 |
| 501 | 尚未实现的外部作业或手工短信登记不受理 |

错误形式为`{code,message,traceId}`；Security过滤链拒绝时至少有code与message，并通过响应头`X-Trace-Id`提供链路编号。

工作界面接口：GET /notice-recipients?workState=TODO&noticeType=BIRTHDAY 返回通知主题、业务类型、负责人、下一次期限和跟进任务ID；GET /notices?status=DRAFT 返回待发布事项和可见名单进度。默认ALL兼容原调用，TODO只包含已发布且未终结工作。未发布、取消和已闭环记录不进入待办。

## 二 权限与接口索引

原样例角色ADMIN、GRID_WORKER、STATISTICIAN；权限配置以数据库为准。系统参数及用户角色由管理员维护，老人及服务由网格员维护，社区汇总由统计员查看。修改用户密码、角色和状态后旧会话立即失效，需重新登录。

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| GET | `/api/v1/ai/conversations` |  |
| POST | `/api/v1/ai/conversations` |  |
| DELETE | `/api/v1/ai/conversations/{id}` |  |
| GET | `/api/v1/ai/conversations/{id}/messages` |  |
| POST | `/api/v1/ai/conversations/{id}/messages` |  |
| GET | `/api/v1/ai/documents` |  |
| POST | `/api/v1/ai/documents` |  |
| GET | `/api/v1/ai/documents/{id}` |  |
| POST | `/api/v1/ai/documents/{id}/action` |  |
| GET | `/api/v1/ai/documents/{id}/content` |  |
| GET | `/api/v1/ai/providers` |  |
| POST | `/api/v1/ai/providers` |  |
| PUT | `/api/v1/ai/providers/{id}` |  |
| DELETE | `/api/v1/ai/providers/{id}` |  |
| POST | `/api/v1/ai/providers/{id}/activation` |  |
| POST | `/api/v1/ai/providers/{id}/test` |  |
| GET | `/api/v1/ai/status` |  |
| GET | `/api/v1/audit-logs` |  |
| GET | `/api/v1/auth/csrf` | 获取CSRF令牌 登录前和登录后分别获取 |
| POST | `/api/v1/auth/login` | JSON登录，Cookie会话与CSRF防护 |
| POST | `/api/v1/auth/logout` | 注销并销毁会话 |
| GET | `/api/v1/auth/me` | 当前用户、角色、权限与社区 |
| POST | `/api/v1/auth/password` | 本人修改密码，核验当前密码；成功后所有旧会话失效 |
| GET | `/api/v1/auth/password-status` |  |
| GET | `/api/v1/communities` |  |
| POST | `/api/v1/communities` |  |
| PUT | `/api/v1/communities/{id}` |  |
| GET | `/api/v1/communities/{id}/grids` |  |
| GET | `/api/v1/communities/{id}/icon` |  |
| POST | `/api/v1/communities/{id}/icon` |  |
| GET | `/api/v1/communities/{id}/workers` |  |
| GET | `/api/v1/dictionaries` | 本社区网格与需求疫苗字典 |
| GET | `/api/v1/elders` | 本人老人分页，编号精确检索及出生月份疫苗需求筛选 |
| POST | `/api/v1/elders` | 登记老人，事务分配编号并加密 |
| GET | `/api/v1/elders/birthday-care` | 生日月份关怀名单，管理员可跨社区，网格员仅本人负责 |
| GET | `/api/v1/elders/{id}` | 本人档案详情及需求疫苗信息 |
| PATCH | `/api/v1/elders/{id}` | 维护本人档案和需求，校验version |
| GET | `/api/v1/elders/{id}/history` | 本人档案接种和归属时间线 |
| POST | `/api/v1/elders/{id}/transfer` | 管理员原子移交档案及未完成通知跟进 |
| PUT | `/api/v1/elders/{id}/vaccines` | 人工核验具体疫苗周期资格、预约及接种事实 |
| POST | `/api/v1/files` | 上传PNG或JPEG，重编码隔离保存 |
| GET | `/api/v1/files/{id}/content` | 鉴权下载业务附件或本人的导出文件 |
| POST | `/api/v1/files/{id}/finalize` | 上传者人工确认脱敏后启用附件 |
| GET | `/api/v1/followups` | 本人跟进任务分页，支持状态及逾期筛选 |
| POST | `/api/v1/followups` | 为本人未终结通知创建人工跟进，同类型未完成任务去重 |
| GET | `/api/v1/followups/{id}` | 跟进任务及执行历史 |
| POST | `/api/v1/followups/{id}/records` | 幂等记录跟进，原子结束旧任务并维护接收及后续任务 |
| GET | `/api/v1/jobs/{id}` | 仅申请人查看作业状态及脱敏错误 |
| POST | `/api/v1/notice-generations` | 排队按规则生成本人通知；返回持久作业编号 |
| GET | `/api/v1/notice-recipients` | 本人接收对象分页 |
| GET | `/api/v1/notice-recipients/{id}` | 接收对象详情、触达、状态历史和满意度版本 |
| POST | `/api/v1/notice-recipients/{id}/attempts` | 幂等登记触达，同事务变更接收状态和跟进任务 |
| POST | `/api/v1/notice-recipients/{id}/feedback` | 采集或更正满意度，版本追加不覆盖 |
| POST | `/api/v1/notice-recipients/{id}/reopen` | 有原因重开，保留既有触达与事件 |
| GET | `/api/v1/notice-recipients/{id}/sms` |  |
| POST | `/api/v1/notice-recipients/{id}/sms` |  |
| GET | `/api/v1/notice-rules` | 管理员读取本社区通知规则 |
| POST | `/api/v1/notice-rules` | 创建生日或疫苗通知规则 |
| PUT | `/api/v1/notice-rules/{id}` | 更新规则版本与启用状态 |
| POST | `/api/v1/notice-rules/{id}/generate` | 管理员立即按本社区各负责人范围排队生成通知 |
| GET | `/api/v1/notices` | 本人可见通知分页 |
| POST | `/api/v1/notices` | 创建草稿和本人对象名单 |
| GET | `/api/v1/notices/{id}` | 通知内容及本人负责的对象 |
| POST | `/api/v1/notices/{id}/activate` | 激活草稿，冻结名单并生成疫苗提醒任务 |
| POST | `/api/v1/notices/{id}/cancel` | 取消通知及尚未结束的本人对象 |
| GET | `/api/v1/permissions` | 全部权限字典 |
| GET | `/api/v1/report-jobs` |  |
| POST | `/api/v1/report-jobs` | 异步导出Excel或Word月报，返回作业ID |
| GET | `/api/v1/report-jobs/{id}` | 查询本人申请的月报状态与文件编号 |
| GET | `/api/v1/reports/filters` | 报表经办人与疫苗周期筛选字典，不返回老人明细 |
| GET | `/api/v1/reports/metrics` | 月份汇总，权限限制本人或社区，比例保留分子分母 |
| GET | `/api/v1/roles` | 角色和权限关联 |
| PUT | `/api/v1/roles/{id}/permissions` | 替换角色权限，使该角色所有旧会话失效 |
| POST | `/api/v1/settings/grids` | 管理员新增本社区网格 |
| POST | `/api/v1/settings/service-needs` | 新增服务需求字典，不修改档案表结构 |
| POST | `/api/v1/settings/vaccine-campaigns` | 新增年度周期和接种阶段，资格由授权人员核验 |
| POST | `/api/v1/settings/vaccine-products` | 新增疫苗产品字典 |
| GET | `/api/v1/sms/settings` |  |
| PUT | `/api/v1/sms/settings` |  |
| GET | `/api/v1/users` | 本社区工作人员列表，不返回密码散列 |
| POST | `/api/v1/users` | 创建本社区应用用户及角色 |
| PATCH | `/api/v1/users/{id}` | 变更工作人员状态角色，旧会话失效 |
| POST | `/api/v1/users/{id}/password` | 管理员重置应用用户密码，不涉及MySQL密码 |
| POST | `/api/v1/users/{id}/reset-password` | 管理员重置应用用户密码，不涉及MySQL密码 |
| GET | `/api/v1/work/filters` | 当前社区可查看的负责人选项，网格员仅返回本人 |
| GET | `/api/v1/work/overview` | 管理员按网格员查看当前社区联系闭环与跟进完成情况 |

共86个HTTP操作。下节逐项列出路径、查询、请求头及请求体；结构类型在第四节展开。

## 三 逐接口契约

### 1 /api/v1/ai/conversations

`GET /api/v1/ai/conversations`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 2 /api/v1/ai/conversations

`POST /api/v1/ai/conversations`

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 3 /api/v1/ai/conversations/{id}

`DELETE /api/v1/ai/conversations/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 4 /api/v1/ai/conversations/{id}/messages

`GET /api/v1/ai/conversations/{id}/messages`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 5 /api/v1/ai/conversations/{id}/messages

`POST /api/v1/ai/conversations/{id}/messages`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Question`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 6 /api/v1/ai/documents

`GET /api/v1/ai/documents`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |
| q | query | 否 | string | default= |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 7 /api/v1/ai/documents

`POST /api/v1/ai/documents`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| title | query | 是 | string | — |
| category | query | 否 | string | default=OTHER |
| issuer | query | 否 | string | default= |
| policyNo | query | 否 | string | default= |
| effectiveDate | query | 否 | string (date) | — |
| expiryDate | query | 否 | string (date) | — |
| isDemo | query | 否 | boolean | default=False |
| redactionConfirmed | query | 否 | boolean | default=False |

请求体：`multipart/form-data`，结构`object`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 8 /api/v1/ai/documents/{id}

`GET /api/v1/ai/documents/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 9 /api/v1/ai/documents/{id}/action

`POST /api/v1/ai/documents/{id}/action`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Change`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 10 /api/v1/ai/documents/{id}/content

`GET /api/v1/ai/documents/{id}/content`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

成功返回文件二进制流和Content-Disposition，不使用data包装。尚未关联的附件仅上传者可读，关联后按当前负责人的业务权限读取，原上传者移交后失去读取权；报表需申请人且仍具有对应范围导出权限，过期文件不可下载。

### 11 /api/v1/ai/providers

`GET /api/v1/ai/providers`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 12 /api/v1/ai/providers

`POST /api/v1/ai/providers`

请求体：`application/json`，结构`Draft`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 13 /api/v1/ai/providers/{id}

`PUT /api/v1/ai/providers/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Draft`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 14 /api/v1/ai/providers/{id}

`DELETE /api/v1/ai/providers/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 15 /api/v1/ai/providers/{id}/activation

`POST /api/v1/ai/providers/{id}/activation`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Toggle`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 16 /api/v1/ai/providers/{id}/test

`POST /api/v1/ai/providers/{id}/test`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 17 /api/v1/ai/status

`GET /api/v1/ai/status`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 18 /api/v1/audit-logs

`GET /api/v1/audit-logs`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |
| communityId | query | 否 | integer (int64) | — |
| action | query | 否 | string | default= |
| actor | query | 否 | string | default= |
| result | query | 否 | string | default= |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 19 获取CSRF令牌 登录前和登录后分别获取

`GET /api/v1/auth/csrf`

无登录要求。返回data.headerName及data.token；请求参数不需要填写CsrfToken对象。

### 20 JSON登录，Cookie会话与CSRF防护

`POST /api/v1/auth/login`

请求体：`application/json`，结构`Login`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

无登录要求，但要求CSRF。返回当前账号id、username、communityId、roles和permissions。连续错误达到阈值会暂时锁定；错误不区分账号不存在和密码错误。

### 21 注销并销毁会话

`POST /api/v1/auth/logout`

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 22 当前用户、角色、权限与社区

`GET /api/v1/auth/me`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 23 本人修改密码，核验当前密码；成功后所有旧会话失效

`POST /api/v1/auth/password`

请求体：`application/json`，结构`PasswordChange`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 24 /api/v1/auth/password-status

`GET /api/v1/auth/password-status`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 25 /api/v1/communities

`GET /api/v1/communities`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 26 /api/v1/communities

`POST /api/v1/communities`

请求体：`application/json`，结构`Draft`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 27 /api/v1/communities/{id}

`PUT /api/v1/communities/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Draft`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 28 /api/v1/communities/{id}/grids

`GET /api/v1/communities/{id}/grids`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 29 /api/v1/communities/{id}/icon

`GET /api/v1/communities/{id}/icon`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 30 /api/v1/communities/{id}/icon

`POST /api/v1/communities/{id}/icon`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |
| redactionConfirmed | query | 否 | boolean | default=False |

请求体：`multipart/form-data`，结构`object`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 31 /api/v1/communities/{id}/workers

`GET /api/v1/communities/{id}/workers`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 32 本社区网格与需求疫苗字典

`GET /api/v1/dictionaries`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 33 本人老人分页，编号精确检索及出生月份疫苗需求筛选

`GET /api/v1/elders`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |
| elderNo | query | 否 | string | — |
| birthMonth | query | 否 | integer (int32) | — |
| vaccinationStatus | query | 否 | string | — |
| needId | query | 否 | integer (int64) | — |
| name | query | 否 | string | — |
| phone | query | 否 | string | — |
| birthFrom | query | 否 | string | — |
| birthTo | query | 否 | string | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 34 登记老人，事务分配编号并加密

`POST /api/v1/elders`

请求体：`application/json`，结构`ElderDraft`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 35 生日月份关怀名单，管理员可跨社区，网格员仅本人负责

`GET /api/v1/elders/birthday-care`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| month | query | 否 | string | — |
| page | query | 否 | integer (int32) | default=1 |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 36 本人档案详情及需求疫苗信息

`GET /api/v1/elders/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 37 维护本人档案和需求，校验version

`PATCH /api/v1/elders/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Edit`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 38 本人档案接种和归属时间线

`GET /api/v1/elders/{id}/history`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 39 管理员原子移交档案及未完成通知跟进

`POST /api/v1/elders/{id}/transfer`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Transfer`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 40 人工核验具体疫苗周期资格、预约及接种事实

`PUT /api/v1/elders/{id}/vaccines`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Vaccine`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

当前状态及核验历史同事务；未知资格不计分母。已接种需日期和针次，已有接种状态的更正追加CORRECTION历史。

### 41 上传PNG或JPEG，重编码隔离保存

`POST /api/v1/files`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| redactionConfirmed | query | 否 | boolean | default=False |

请求体：`multipart/form-data`，结构`object`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

multipart字段`file`和`redactionConfirmed=true`，仅有效JPEG或PNG，最多20MB及2500万像素，后端重编码为PNG移除原元数据；返回id、TEMP和PENDING，不能直接用于确认凭证。

### 42 鉴权下载业务附件或本人的导出文件

`GET /api/v1/files/{id}/content`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

成功返回文件二进制流和Content-Disposition，不使用data包装。尚未关联的附件仅上传者可读，关联后按当前负责人的业务权限读取，原上传者移交后失去读取权；报表需申请人且仍具有对应范围导出权限，过期文件不可下载。

### 43 上传者人工确认脱敏后启用附件

`POST /api/v1/files/{id}/finalize`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Confirm`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

上传者明确确认脱敏后返回id与ACTIVE；不代表系统已自动识别所有隐私或接入第三方杀毒。

### 44 本人跟进任务分页，支持状态及逾期筛选

`GET /api/v1/followups`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |
| status | query | 否 | string | — |
| overdue | query | 否 | boolean | default=False |
| recipientId | query | 否 | integer (int64) | — |
| noticeType | query | 否 | string | — |
| ownerId | query | 否 | integer (int64) | — |
| taskType | query | 否 | string | — |
| deadline | query | 否 | string | default=ALL |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 45 为本人未终结通知创建人工跟进，同类型未完成任务去重

`POST /api/v1/followups`

请求体：`application/json`，结构`NewTask`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 46 跟进任务及执行历史

`GET /api/v1/followups/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 47 幂等记录跟进，原子结束旧任务并维护接收及后续任务

`POST /api/v1/followups/{id}/records`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |
| Idempotency-Key | header | 是 | string | — |

请求体：`application/json`，结构`Record`。

必须提供`Idempotency-Key`，1至60位英文、数字或`_.:-`。同键同内容返回原记录或作业；同键不同内容409。触达和跟进重试可沿用第一次的version，服务端先判断幂等重放。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 48 仅申请人查看作业状态及脱敏错误

`GET /api/v1/jobs/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 49 排队按规则生成本人通知；返回持久作业编号

`POST /api/v1/notice-generations`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| Idempotency-Key | header | 是 | string | — |

请求体：`application/json`，结构`Generation`。

必须提供`Idempotency-Key`，1至60位英文、数字或`_.:-`。同键同内容返回原记录或作业；同键不同内容409。触达和跟进重试可沿用第一次的version，服务端先判断幂等重放。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

返回data.id作业编号；生成范围为当前网格员本人。规则需由管理员先启用。

### 50 本人接收对象分页

`GET /api/v1/notice-recipients`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |
| receiptStatus | query | 否 | string | — |
| noticeType | query | 否 | string | — |
| workState | query | 否 | string | default=ALL |
| noticeId | query | 否 | integer (int64) | — |
| ownerId | query | 否 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 51 接收对象详情、触达、状态历史和满意度版本

`GET /api/v1/notice-recipients/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 52 幂等登记触达，同事务变更接收状态和跟进任务

`POST /api/v1/notice-recipients/{id}/attempts`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |
| Idempotency-Key | header | 是 | string | — |

请求体：`application/json`，结构`Attempt`。

必须提供`Idempotency-Key`，1至60位英文、数字或`_.:-`。同键同内容返回原记录或作业；同键不同内容409。触达和跟进重试可沿用第一次的version，服务端先判断幂等重放。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 53 采集或更正满意度，版本追加不覆盖

`POST /api/v1/notice-recipients/{id}/feedback`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Feedback`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 54 有原因重开，保留既有触达与事件

`POST /api/v1/notice-recipients/{id}/reopen`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Reason`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

保留原触达和事件，重开接收对象并将批次恢复ACTIVE。必须说明原因，不能修改原反馈或伪造成功。

### 55 /api/v1/notice-recipients/{id}/sms

`GET /api/v1/notice-recipients/{id}/sms`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 56 /api/v1/notice-recipients/{id}/sms

`POST /api/v1/notice-recipients/{id}/sms`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |
| Idempotency-Key | header | 是 | string | — |

请求体：`application/json`，结构`Version`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 57 管理员读取本社区通知规则

`GET /api/v1/notice-rules`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 58 创建生日或疫苗通知规则

`POST /api/v1/notice-rules`

请求体：`application/json`，结构`Rule`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 59 更新规则版本与启用状态

`PUT /api/v1/notice-rules/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Rule`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 60 管理员立即按本社区各负责人范围排队生成通知

`POST /api/v1/notice-rules/{id}/generate`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |
| Idempotency-Key | header | 是 | string | — |

请求体：`application/json`，结构`Generation`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 61 本人可见通知分页

`GET /api/v1/notices`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |
| noticeType | query | 否 | string | — |
| status | query | 否 | string | — |
| ownerId | query | 否 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 62 创建草稿和本人对象名单

`POST /api/v1/notices`

请求体：`application/json`，结构`NoticeDraft`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 63 通知内容及本人负责的对象

`GET /api/v1/notices/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 64 激活草稿，冻结名单并生成疫苗提醒任务

`POST /api/v1/notices/{id}/activate`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Version`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 65 取消通知及尚未结束的本人对象

`POST /api/v1/notices/{id}/cancel`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Reason`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 66 全部权限字典

`GET /api/v1/permissions`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 67 /api/v1/report-jobs

`GET /api/v1/report-jobs`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |

必须提供`Idempotency-Key`，1至60位英文、数字或`_.:-`。同键同内容返回原记录或作业；同键不同内容409。触达和跟进重试可沿用第一次的version，服务端先判断幂等重放。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 68 异步导出Excel或Word月报，返回作业ID

`POST /api/v1/report-jobs`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| Idempotency-Key | header | 是 | string | — |

请求体：`application/json`，结构`Export`。

必须提供`Idempotency-Key`，1至60位英文、数字或`_.:-`。同键同内容返回原记录或作业；同键不同内容409。触达和跟进重试可沿用第一次的version，服务端先判断幂等重放。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

返回data.reportJobId和data.jobId。轮询GET /jobs/{jobId}或GET /report-jobs/{reportJobId}，完成后用outputFileId下载。

### 69 查询本人申请的月报状态与文件编号

`GET /api/v1/report-jobs/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 70 报表经办人与疫苗周期筛选字典，不返回老人明细

`GET /api/v1/reports/filters`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| communityId | query | 否 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 71 月份汇总，权限限制本人或社区，比例保留分子分母

`GET /api/v1/reports/metrics`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| q | query | 是 | Query | — |

返回month、ownerId、metricVersion、snapshotAt、cutoffAt及metrics数组。每个指标含code、dimension、numerator、denominator、value、nullReason。通知与反馈按事件或版本截止读取，疫苗按历史核验读取。

### 72 角色和权限关联

`GET /api/v1/roles`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 73 替换角色权限，使该角色所有旧会话失效

`PUT /api/v1/roles/{id}/permissions`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`RoleChange`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 74 管理员新增本社区网格

`POST /api/v1/settings/grids`

请求体：`application/json`，结构`Name`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 75 新增服务需求字典，不修改档案表结构

`POST /api/v1/settings/service-needs`

请求体：`application/json`，结构`Name`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 76 新增年度周期和接种阶段，资格由授权人员核验

`POST /api/v1/settings/vaccine-campaigns`

请求体：`application/json`，结构`Campaign`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 77 新增疫苗产品字典

`POST /api/v1/settings/vaccine-products`

请求体：`application/json`，结构`Name`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 78 /api/v1/sms/settings

`GET /api/v1/sms/settings`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 79 /api/v1/sms/settings

`PUT /api/v1/sms/settings`

请求体：`application/json`，结构`Settings`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 80 本社区工作人员列表，不返回密码散列

`GET /api/v1/users`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer (int32) | default=1 |
| pageSize | query | 否 | integer (int32) | default=20 |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 81 创建本社区应用用户及角色

`POST /api/v1/users`

请求体：`application/json`，结构`UserCreate`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 82 变更工作人员状态角色，旧会话失效

`PATCH /api/v1/users/{id}`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`UserChange`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 83 管理员重置应用用户密码，不涉及MySQL密码

`POST /api/v1/users/{id}/password`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Password`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 84 管理员重置应用用户密码，不涉及MySQL密码

`POST /api/v1/users/{id}/reset-password`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer (int64) | — |

请求体：`application/json`，结构`Password`。

写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 85 当前社区可查看的负责人选项，网格员仅返回本人

`GET /api/v1/work/filters`

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

### 86 管理员按网格员查看当前社区联系闭环与跟进完成情况

`GET /api/v1/work/overview`

| 参数 | 位置 | 必填 | 类型 | 约束 |
| --- | --- | --- | --- | --- |
| noticeType | query | 否 | string | — |
| ownerId | query | 否 | integer (int64) | — |

响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。

## 四 请求对象字段字典

以下必填项以实际OpenAPI的required为准。ID请求可用数字或十进制字符串，建议前端统一字符串。可选字段省略或为null；空集合请显式传[]。

### Attempt

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| channel | string | 是 | HOME_VISIT、FAMILY_RELAY、BULLETIN；短信通过独立发送入口调用备案网关，不允许手工登记虚假短信成功；pattern=HOME_VISIT或FAMILY_RELAY或BULLETIN或SMS |
| result | string | 是 | POSTED、CONFIRMED、UNREACHABLE或REFUSED；须匹配渠道；pattern=POSTED或CONFIRMED或UNREACHABLE或REFUSED |
| unreachableReason | string | 否 | NO_ONE_HOME、NO_ANSWER或OTHER；未联系上必填；minLength=0; maxLength=32 |
| reasonText | string | 否 | 拒绝或其他未联系原因必填，客观描述；minLength=0; maxLength=300 |
| feedbackQuote | string | 否 | 客观反馈原话，但需移除禁采信息；minLength=0; maxLength=500 |
| familyNameMasked | string | 否 | 兼容旧客户端的脱敏称谓；新客户端使用familyName，实际姓名单独加密保存；minLength=0; maxLength=40 |
| familyRelation | string | 否 | 家属关系，例如女儿；minLength=0; maxLength=32 |
| relayConfirmed | boolean | 否 | 家属明确确认已转告；家属渠道确认接收必须true；— |
| fileIds | array<integer (int64)> | 是 | 已由本人上传并确认脱敏的附件ID集合；无附件传[]；元素去重 |
| familyName | string | 否 | 家属姓名或称呼，专用加密字段；minLength=0; maxLength=40 |

### Campaign

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| vaccineId | integer (int64) | 是 | — |
| cycleKey | string | 是 | minLength=0; maxLength=32 |
| doseStage | string | 是 | minLength=0; maxLength=32 |
| name | string | 是 | 老人姓名（personalInfo内）；列表查询时为姓名精确匹配；minLength=0; maxLength=100 |
| startDate | string (date) | 是 | 自定义统计开始日期YYYY-MM-DD，须与endDate同时填写；— |
| endDate | string (date) | 是 | 自定义统计结束日期YYYY-MM-DD，包含当天；— |
| eligibilityRuleRef | string | 否 | minLength=0; maxLength=200 |

### Change

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| action | string | 是 | pattern=retry或enable或disable或delete; minLength=1 |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |

### Confirm

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| redactionConfirmed | boolean | 否 | 上传前必须true，表示文档/图片已人工确认脱敏；— |

### Draft

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| code | string | 是 | pattern=[A-Z0-9_-]{1,32}; minLength=1 |
| name | string | 是 | 老人姓名（personalInfo内）；列表查询时为姓名精确匹配；minLength=0; maxLength=80 |
| description | string | 否 | minLength=0; maxLength=2000 |
| status | string | 是 | pattern=ACTIVE或INACTIVE; minLength=1 |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |

### Edit

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| profile | ElderDraft | 是 | — |
| status | string | 是 | pattern=ACTIVE或INACTIVE或ARCHIVED |

### ElderDraft

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| gridId | integer (int64) | 是 | 本社区有效网格ID；— |
| sex | string | 是 | pattern=MALE或FEMALE或UNKNOWN |
| birthMonth | string | 是 | 出生年月YYYY-MM，不包含出生日期；不得为未来月份；pattern=\d{4}-(0[1-9]或1[0-2]) |
| remark | string | 否 | 业务备注；联系方式填写到专用加密字段，不在此明文保存；minLength=0; maxLength=500 |
| needIds | array<integer (int64)> | 是 | 有效服务需求ID数组；无需求时传[]；元素去重 |
| ownerUserId | integer (int64) | 否 | 本社区有效网格员ID；— |
| personalInfo | Info | 是 | 必填加密资料对象：姓名必填；本人电话、住址、家属姓名、关系和电话可选；禁止身份证号；— |

### Export

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| query | Query | 是 | 月报查询条件，结构见Query；— |
| format | string | 是 | XLSX或DOCX；pattern=XLSX或DOCX |

### Feedback

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| revision | integer (int32) | 否 | 反馈当前版本，首次0，更正时传最新revisionNo；minimum=0 |
| rating | string | 是 | SATISFIED、NEUTRAL、DISSATISFIED；pattern=SATISFIED或NEUTRAL或DISSATISFIED |
| respondentType | string | 是 | ELDER或FAMILY；pattern=ELDER或FAMILY |
| text | string | 否 | minLength=0; maxLength=500 |

### Generation

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| ruleId | integer (int64) | 是 | 本社区已启用通知规则ID；— |
| period | string | 是 | 自动生成目标月份YYYY-MM；pattern=\d{4}-(0[1-9]或1[0-2]) |

### Info

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| name | string | 是 | 老人姓名（personalInfo内）；列表查询时为姓名精确匹配；minLength=0; maxLength=40 |
| phone | string | 否 | 老人本人手机号或带区号固话，支持+86；列表查询时为规范化后的精确匹配；minLength=0; maxLength=24 |
| address | string | 否 | 家庭住址，专用加密字段，最长200字符；minLength=0; maxLength=200 |
| familyName | string | 否 | 家属姓名或称呼，专用加密字段；minLength=0; maxLength=40 |
| familyRelation | string | 否 | 家属关系，例如女儿；minLength=0; maxLength=24 |
| familyPhone | string | 否 | 家属电话，专用加密字段；minLength=0; maxLength=24 |
| familySmsAllowed | boolean | 否 | 家属手机号已备案并获短信通知授权；默认false，排队及发送时重新核验；— |

### Login

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| username | string | 是 | 应用登录账号，不使用手机号；minLength=0; maxLength=64 |
| password | string | 是 | 应用登录密码；创建/管理员重置固定123456，可省略；不是MySQL密码；minLength=0; maxLength=128 |

### Name

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| code | string | 是 | pattern=[A-Z0-9_-]{1,32}; minLength=1 |
| name | string | 是 | 老人姓名（personalInfo内）；列表查询时为姓名精确匹配；minLength=0; maxLength=64 |

### NewTask

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| recipientId | integer (int64) | 是 | 仅查看这条联系记录的跟进任务，仍校验当前权限；— |
| type | string | 是 | 具体枚举以pattern为准；通知类型或跟进类型，不能混用；pattern=RECONTACT或VERIFY_APPOINTMENT或PRE_VACCINE_REMINDER |
| dueAt | string (date-time) | 是 | 任务截止时间，ISO8601含时区，例如2026-10-20T09:00:00+08:00；— |

### NoticeDraft

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| type | string | 是 | 具体枚举以pattern为准；通知类型或跟进类型，不能混用；pattern=VACCINE或BIRTHDAY或LECTURE或OTHER |
| title | string | 是 | minLength=0; maxLength=120 |
| content | string | 是 | minLength=0; maxLength=5000 |
| elderIds | array<integer (int64)> | 是 | 本人负责且启用的老人ID集合，最多1000人；minItems=0; maxItems=1000; 元素去重 |
| campaignId | integer (int64) | 否 | 具体年度与阶段的疫苗服务周期；疫苗通知和规则必填；— |
| periodKey | string | 是 | 通知的业务周期，例如2026-10；minLength=0; maxLength=32 |

### Password

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| password | string | 否 | 应用登录密码；创建/管理员重置固定123456，可省略；不是MySQL密码；minLength=0; maxLength=72 |

### PasswordChange

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| currentPassword | string | 是 | 本人当前密码，修改前验证；minLength=0; maxLength=128 |
| newPassword | string | 是 | 至少6个字符且最多72个UTF-8字节，显示强度；不强制12位；minLength=6; maxLength=72 |
| confirmPassword | string | 是 | 必须与newPassword一致；minLength=0; maxLength=72 |

### Query

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| month | string | 是 | 统计月份YYYY-MM，边界使用Asia/Shanghai；pattern=\d{4}-(0[1-9]或1[0-2]) |
| ownerId | integer (int64) | 否 | 经办人/负责网格员；管理员可筛选当前社区人员，网格员始终与本人权限范围取交集，统计接口按报表权限处理；— |
| noticeType | string | 否 | 业务分类：VACCINE、BIRTHDAY、LECTURE、OTHER；pattern=VACCINE或BIRTHDAY或LECTURE或OTHER |
| campaignId | integer (int64) | 否 | 具体年度与阶段的疫苗服务周期；疫苗通知和规则必填；— |
| communityId | integer (int64) | 否 | — |
| startDate | string (date) | 否 | 自定义统计开始日期YYYY-MM-DD，须与endDate同时填写；— |
| endDate | string (date) | 否 | 自定义统计结束日期YYYY-MM-DD，包含当天；— |

### Question

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| question | string | 是 | minLength=0; maxLength=2000 |
| requestId | string | 是 | pattern=[0-9a-fA-F-]{36}; minLength=1 |

### Reason

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| reason | string | 是 | 移交、取消、更正或核验的客观说明；minLength=0; maxLength=300 |

### Record

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| outcome | string | 是 | RESOLVED、RETRY_REQUIRED、REFUSED、NO_LONGER_APPLICABLE；pattern=RESOLVED或RETRY_REQUIRED或REFUSED或NO_LONGER_APPLICABLE |
| resultText | string | 是 | 跟进执行结果，不能只点完成而无说明；minLength=0; maxLength=1000 |
| nextDueAt | string (date-time) | 否 | 再次跟进期限，含时区；RETRY_REQUIRED必须在未来；— |
| attempt | Attempt | 否 | 本次触达对象；重新联系和接种前提醒解决时需确认触达；— |

### RoleChange

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| permissionIds | array<integer (int64)> | 是 | 有效权限ID集合，不能移除管理员的用户及角色管理权限；minItems=1; 元素去重 |

### Rule

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| code | string | 是 | pattern=[A-Z0-9_]{1,32}; minLength=1 |
| name | string | 是 | 老人姓名（personalInfo内）；列表查询时为姓名精确匹配；minLength=0; maxLength=80 |
| noticeType | string | 是 | 业务分类：VACCINE、BIRTHDAY、LECTURE、OTHER；pattern=BIRTHDAY或VACCINE |
| leadDays | integer (int32) | 否 | 提前准备天数，0至30；生日规则用于选择目标月份；minimum=0; maximum=30 |
| campaignId | integer (int64) | 否 | 具体年度与阶段的疫苗服务周期；疫苗通知和规则必填；— |
| enabled | boolean | 否 | — |

### Settings

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| enabled | boolean | 否 | — |
| endpoint | string | 否 | minLength=0; maxLength=500 |
| apiKey | string | 否 | minLength=0; maxLength=500 |
| signature | string | 否 | minLength=0; maxLength=80 |
| templateId | string | 否 | minLength=0; maxLength=100 |

### Toggle

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| active | boolean | 否 | — |

### Transfer

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| gridId | integer (int64) | 是 | 本社区有效网格ID；— |
| ownerUserId | integer (int64) | 是 | 本社区有效网格员ID；— |
| reason | string | 是 | 移交、取消、更正或核验的客观说明；minLength=0; maxLength=300 |
| communityId | integer (int64) | 否 | — |

### UserChange

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| displayName | string | 是 | minLength=0; maxLength=64 |
| status | string | 是 | pattern=ACTIVE或DISABLED |
| roleIds | array<integer (int64)> | 是 | 有效角色ID集合，管理员1、网格员2、统计员3为原样例ID；minItems=1; 元素去重 |
| communityIds | array<integer (int64)> | 否 | 元素去重 |

### UserCreate

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| username | string | 是 | 应用登录账号，不使用手机号；pattern=[a-zA-Z0-9_]{3,64}; minLength=1 |
| password | string | 否 | 应用登录密码；创建/管理员重置固定123456，可省略；不是MySQL密码；minLength=0; maxLength=72 |
| displayName | string | 是 | minLength=0; maxLength=64 |
| roleIds | array<integer (int64)> | 是 | 有效角色ID集合，管理员1、网格员2、统计员3为原样例ID；minItems=1; 元素去重 |
| communityIds | array<integer (int64)> | 否 | 元素去重 |

### Vaccine

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |
| campaignId | integer (int64) | 是 | 具体年度与阶段的疫苗服务周期；疫苗通知和规则必填；— |
| eligibility | string | 是 | ELIGIBLE、INELIGIBLE、UNKNOWN，未知不进接种率分母；pattern=ELIGIBLE或INELIGIBLE或UNKNOWN |
| vaccinationStatus | string | 是 | NOT_VACCINATED、PARTIAL、COMPLETED、UNKNOWN；pattern=NOT_VACCINATED或PARTIAL或COMPLETED或UNKNOWN |
| appointmentDate | string (date) | 否 | 授权人员确认的预约日期，可为空；— |
| vaccinatedOn | string (date) | 否 | 接种日期；PARTIAL或COMPLETED时必填，不能为未来；— |
| doseNo | integer (int32) | 否 | 实际针次，接种事实必填且至少1；minimum=1 |
| reason | string | 是 | 移交、取消、更正或核验的客观说明；minLength=0; maxLength=300 |

### Version

| 字段 | 类型 | 必填 | 规则与说明 |
| --- | --- | --- | --- |
| version | integer (int32) | 否 | 当前资源版本；先GET最新值，旧版本409；保持JSON整数；minimum=0 |

## 五 核心调用示例

下列路径都相对于/api/v1；Cookie与CSRF省略展示但实际调用不可省略。样例ID以当前数据库查询为准，不要把文档中的ID当作永远固定。

### 首次启用网格员

管理员GET /users获得当前version。按需POST /users/2/reset-password重置为123456，再PATCH /users/2启用；每次写入version都会递增，不能在两步里重复使用旧版本。

```json
{"version":0}
```

```json
{"version":1,"displayName":"演示网格员","status":"ACTIVE","roleIds":["2"],"communityIds":["1"]}
```

### 登记老人

POST /elders（示例ID需按本机字典替换，管理员须指定有效负责网格员）：

```json
{
  "gridId": "1",
  "ownerUserId": "3",
  "sex": "MALE",
  "birthMonth": "1950-06",
  "remark": "日常关怀",
  "needIds": [
    "1",
    "2"
  ],
  "personalInfo": {
    "name": "宋江",
    "phone": "",
    "address": "",
    "familyName": "",
    "familyRelation": "",
    "familyPhone": "",
    "familySmsAllowed": false
  }
}
```

响应示例为`{"data":{"id":"4","elderNo":"SH-L-2026-004","version":0},"traceId":"..."}`，编号按实际序列分配；详情和列表向授权人员返回name、phone、address、familyName、familyRelation、familyPhone，不返回任何密文或HMAC。姓名和联系资料仅在personalInfo专用字段提交后加密保存；禁止身份证号。

PATCH /elders/{id}采用`{"version":0,"profile":{...ElderDraft字段...},"status":"ACTIVE"}`，属于明确的整份业务资料替换，不是任意字段穿透修改。

### 创建并激活通知

POST /notices：

```json
{
  "type": "BIRTHDAY",
  "title": "十月生日关怀",
  "content": "请经办人开展生日月份关怀。",
  "elderIds": [
    "1"
  ],
  "periodKey": "2026-10"
}
```

取得id后POST /notices/{id}/activate，传`{"version":0}`。激活后接收对象version会递增，登记触达前应GET最新接收对象。VACCINE类型必须有campaignId；已预约时生成接种前3天提醒，未知预约时生成核实任务。

### 家属转告及确认接收

POST /notice-recipients/{id}/attempts，头`Idempotency-Key: visit-20261016-001`：

```json
{
  "version": 1,
  "channel": "FAMILY_RELAY",
  "result": "CONFIRMED",
  "familyName": "张某",
  "familyRelation": "女儿",
  "relayConfirmed": true,
  "feedbackQuote": "已经转告，同意参加",
  "fileIds": []
}
```

上门无人使用channel=HOME_VISIT、result=UNREACHABLE、unreachableReason=NO_ONE_HOME；reasonText记录客观原因。上门确认接收必须先上传图片、确认脱敏，再把文件id放fileIds。公告栏用BULLETIN加POSTED及照片，不认定个人接收。短信通过POST /notice-recipients/{id}/sms排队发送；手工触达登记接口拒绝SMS，避免伪造发送成功。

### 完成跟进

先GET /followups/{id}和对应通知对象最新version，再POST /followups/{id}/records，头`Idempotency-Key: followup-001`：

```json
{
  "version": 0,
  "outcome": "RESOLVED",
  "resultText": "家属明确确认已转告",
  "attempt": {
    "version": 0,
    "channel": "FAMILY_RELAY",
    "result": "CONFIRMED",
    "familyName": "张某",
    "familyRelation": "女儿",
    "relayConfirmed": true,
    "feedbackQuote": "已转告",
    "fileIds": []
  }
}
```

旧任务先结束，本次触达与后续任务同事务提交；任一失败全部回滚。RETRY_REQUIRED需未来nextDueAt；NO_LONGER_APPLICABLE需说明不再适用原因，关闭不计作接收成功。核实预约任务解决前须先登记真实预约日期，接种前提醒任务解决须包含确认触达。

### 满意度与报表

首次POST /notice-recipients/{id}/feedback：`{"revision":0,"rating":"SATISFIED","respondentType":"FAMILY","text":"服务说明清楚"}`；更正时传当前最高revisionNo，新增一条版本，不覆盖旧评价。

GET /reports/metrics?month=2026-10；统计员默认社区汇总，网格员默认本人。POST /report-jobs：

```json
{
  "query": {
    "month": "2026-10"
  },
  "format": "XLSX"
}
```

加Idempotency-Key。改format=DOCX可导出Word。拿reportJobId轮询，status=COMPLETED后GET /files/{outputFileId}/content下载。输出只有汇总，无隐藏个人明细。

## 六 状态与统计口径

通知批次：DRAFT→ACTIVE→COMPLETED或CANCELLED。接收状态：PENDING、CONFIRMED、UNREACHABLE、REFUSED。业务状态：OPEN、FOLLOWING、CLOSED、CANCELLED。拒绝及重试耗尽可形成闭环，但不增加已确认接收人数。

跟进：PENDING、PROCESSING、COMPLETED、CANCELLED。逾期为未结束且dueAt早于当前时间，不覆盖原状态。默认次日重试，最多3次；达到上限应由经办人说明原因终结，不能自动记成功。

通知覆盖率按接收对象去重计数；满意度分母只有有效反馈，同时显示反馈率；接种率按周期及已核验符合资格的人数计算；渠道使用率按实际触达尝试次数；分母0返回null及说明。月度边界采用上海时区半开区间。

## 七 接入限制与微服务边界

短信通过可配置备案网关发送，须核验家属号码与明确授权，当前尚未配置真实平台。卫生服务中心同步和街道上报平台暂无实际接口。AI政策知识库及RAG受现有会话与社区权限约束，不向模型提供老人原始档案。

完整系统使用41张表，部署时先按顺序升级V002、V003和V004。初始/重置密码按要求为123456，用户可自助修改；没有通用任意表CRUD、原始SQL执行接口或匿名注册。详细运行、测试和当前边界见backend/README.md。
