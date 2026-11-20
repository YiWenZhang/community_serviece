**当前1.5.0增加老人姓名、电话、住址及家属资料加密存储，需要执行V003；重建四社区脚本会自动升级。简版更新命令见[11.20启动更新](../11.20启动更新.md)，完整数据与政策设计见[四社区初始数据导入说明](../四社区初始数据导入说明.md)。初始化维护入口仅在命令行显式执行，不会随启动自动清理数据。**

**密码、加密及HTTPS检查见[密码与安全检查说明](../密码与安全检查说明.md)。默认整站启动为HTTPS，下面单独后端启动的HTTP地址仅供虚构数据调试。**

# 社区老年服务业务微服务

**升级1.2.0前，必须先按[多社区权限升级说明](../多社区权限升级说明.md)备份并执行V002增量SQL。**

`community-service`是可独立启动和部署的Spring Boot后端。1.1.0已增加政策知识库、AI结构化解析、Lucene持久向量索引、RAG问答与可切换模型配置，沿用现有会话与角色权限。详见[政策问答启动说明](../政策问答启动说明.md)。

## 技术与目录

Java21编译目标，Spring Boot4.1.1，Spring Security7，Spring JDBC，MySQL8.0.42或8.4，Springdoc3.1.1，Apache POI5.4.1，Maven。当前电脑JDK22可编译并运行生成的Java21字节码。

```text
backend/
  src/main/java/cn/community/
    identity/    登录、用户、角色、权限、配置
    elder/       档案、归属、接种核验
    notice/      通知、触达、反馈、规则
    followup/    跟进任务及执行
    files/       脱敏附件、私有下载
    reporting/   指标快照、Excel和Word
    jobs/        MySQL持久队列及工作器
    common/      SQL、加密、错误、审计
    config/      安全、启动、OpenAPI
  src/test/      加密校验与真实MySQL集成测试
  docs/          详细接口文档、OpenAPI、验证记录
  scripts/       隔离环境HTTP验证及测试工具
  config/        生产配置示例
  start-local.ps1
  Dockerfile
```

本版本仍在单个业务服务内部按领域分包，通知和跟进通过本地事务实现闭环；不是把六个模块拆成六个远程服务。没有引入注册中心、配置中心或分布式事务。部署多个副本前需补共享Session及文件存储。

## 一 本机启动

项目业务数据库已在前一步创建。1.1.0仅增量创建AI表并首次赋予内置角色AI权限；不重建业务表、不重导样例、不删除数据库，也不修改MySQL密码。请先确认`MySQL80`服务运行。

在PowerShell执行：

```powershell
cd D:\Desktop\CummityService\backend
mvn clean package
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1
```

启动脚本依次要求：

1. 现有MySQL账号密码，默认账号root；也可用`-DbUser`指定已有账号。
应用初始密码统一为123456（弱），不再提示设置12位密码。用户登录后可自行修改。

MySQL密码与应用密码相互独立；脚本不保存MySQL密码。脚本只在当前进程及子进程环境中传递；关闭服务后清理密码环境变量。默认地址`127.0.0.1:8080`，按Ctrl和C停止。端口冲突时使用`-Port 8081`。

首次管理员初始化只匹配SQL样例中的禁用`admin_demo`及不可登录标记，启用后绝不由启动脚本重复重置密码。已有活跃管理员密码遗忘时应通过另外的管理员受控重置，不能指望启动参数覆盖。

本机脚本读取`../database/演示数据密钥.json`，保证可以解密已导入的三条虚构编号。正式数据必须迁移到受控密钥，不能直接更换密钥后继续读取旧密文，也不能将演示密钥用于生产。

## 二 访问地址

| 用途 | 地址 |
| --- | --- |
| 健康检查 | http://127.0.0.1:8080/actuator/health |
| Swagger UI | http://127.0.0.1:8080/swagger-ui.html |
| OpenAPI JSON | http://127.0.0.1:8080/v3/api-docs |
| API前缀 | http://127.0.0.1:8080/api/v1 |

接口详情及完整字段见`docs/API接口文档.md`；同目录`openapi.json`可导入Apifox、Postman或其他OpenAPI工具。Swagger页面能在本地读取文档，但执行写接口仍需登录Cookie、CSRF和相应权限。

## 三 第一次使用

1. 启动时激活`admin_demo`，使用自己设置的应用密码登录。
2. 管理员查询用户列表，重置`grid_demo`、`stats_demo`应用密码，再将状态设为ACTIVE，分别授予网格员和统计员角色。也可直接创建新用户。
3. 管理员可切换并管理全部社区的老人、通知和跟进。网格员通过user_community分配一个或多个社区，仍只操作本人负责的业务；统计员只查看全部社区汇总和报表。
4. 网格员可以查看原样例档案，因为其原负责人就是`grid_demo`。新建用户不会自动获得其他人的老人档案，需要管理员移交。
5. 统计员只查看汇总、导出本人申请的报表，不得读老人列表、触达明细或签字图片。

每次变更账号密码、状态或角色都会使旧会话失效。改了自己的角色或密码后，需要重新登录。不要将应用账号`admin_demo`用于DBeaver；DBeaver连接的是MySQL账号。

## 四 前端接入

服务使用服务端Session，不使用JWT。先GET`/api/v1/auth/csrf`取得token，再在每次POST、PATCH、PUT和DELETE请求头发送`X-XSRF-TOKEN`。请求携带Cookie。登录后重新取一次CSRF令牌，再发后续写请求。

Vue开发环境建议使用Vite的`/api`代理转发到本服务，保持浏览器同源；正式部署用Nginx同源代理。没有开放任意来源CORS。Axios开启`withCredentials`并处理401、403、409；不要将Session或个人业务全集持久化到localStorage。

响应形式为`{data,traceId}`，数据库snake_case列转换为camelCase，BIGINT及Long计数为字符串。小整数version、page及pageSize仍为数值。比例value为0到1的小数，前端展示时乘100；分母为0时value为null，不当成0%。

## 五 已实现的业务规则

档案序列使用行锁，按登记年份最少三位并可超过999；编号AES-GCM加密，独立HMAC精确查询。姓名、本人电话、住址及家属联系信息以独立AES-GCM密文JSON保存，姓名及本人电话用HMAC精确检索。档案的授权显示与编辑均受社区和负责人范围控制，统计员不可访问。文本拦截常见手机号、身份证及详细地址模式，但不能代替人工核对所有敏感信息。

通知分批次、接收对象和触达记录；上门确认需脱敏签字图，家属转告需脱敏称谓与明确确认，公告只能说明已张贴，不能直接算个人确认。拒绝、未联系及反馈原话分别记录。触达和跟进执行要求幂等键，同键不同内容返回409。

通知、事件、跟进及审计同事务。确认通知不修改疫苗接种状态。疫苗资格及接种事实由网格员单独核验，历史追加；未知资格不计入接种率分母。预约前三天任务在初次接收确认后继续保留，执行最终提醒后才能关闭对应通知业务。

后台每5秒认领一条持久作业。规则扫描在上海时区每小时第10分钟执行，按规则、周期和老人防重。默认生日规则仍为停用，管理员启用后才自动执行；生日提前天数按配置用于选择当前月或下月。规则变更不自动补发同一周期已存在的对象。

每月1日上海时间01:30，为具有社区导出权限的有效统计员排队生成上月Excel月报，同一用户同一月份去重。服务在该时点停机时，不自动补跑错过的月报，可由统计员手工申请。

报表在MySQL一致性事务中计算指标并保存快照，再生成XLSX或DOCX。通知及反馈按截止时间的事件或版本读取，疫苗按历史核验读取；已导出文件不覆盖，新请求生成新快照。已生成文件默认7天到期。未安装Office也可生成文件。

## 六 配置

| 环境变量 | 默认或含义 |
| --- | --- |
| DB_URL | 本机3306的community_elder_service，UTC连接 |
| DB_USERNAME | root；生产使用专用账号 |
| DB_PASSWORD | 必须使用现有账号密码，空值不会尝试改密 |
| APP_AES_KEY | 32字节AES密钥的64位十六进制字符串 |
| APP_HMAC_KEY | 独立的32字节HMAC密钥，64位十六进制 |
| APP_STORAGE | `./storage`私有目录 |
| SERVER_PORT | 8080 |
| SERVER_ADDRESS | 127.0.0.1；容器示例为0.0.0.0 |
| COOKIE_SECURE | 本机HTTP为false，HTTPS生产必须true |
| APP_PUBLIC_DOCS | 本机true，生产建议false并同时关闭springdoc端点 |
| APP_SCHEDULER_ENABLED | true；测试可false |
| SERVER_SSL_ENABLED / APP_REQUIRE_HTTPS | HTTPS脚本设置true，生产配置强制启用 |

生产配置示例位于`config/application-production.example.yml`。生产必须使用HTTPS、私网MySQL、受控密钥、独立数据卷及数据库备份。普通运行账号不需要DDL权限；审核日志表应限制更新和删除，不能仅凭一张普通表宣称绝对不可篡改。

## 七 打包与部署

```powershell
mvn clean package
java -jar target/community-service-1.5.0.jar
```

独立可执行产物为`target/community-service-1.5.0.jar`。直接java启动前必须配置数据库密码及两把密钥；本机优先使用上述交互脚本。Dockerfile提供Java21镜像和非root运行用户：

```text
docker build -t community-service:1.0.0 .
```

容器的DB_URL应指向可访问的MySQL地址，不能把容器内127.0.0.1误认为电脑上的数据库。以环境或密钥系统注入配置，挂载`/data/community-service`并保证UID10001有写权限；发布服务时通过HTTPS反向代理接入。

AI模块位于`cn.community.ai`，拥有独立的政策、片段、模型配置和问答表，不修改老人、通知表。所有AI接口沿用服务端Session、CSRF和数据库权限，并按当前用户社区隔离；模型配置同时要求ADMIN角色与系统配置权限。

## 八 测试

`mvn test`默认运行无需数据库的加密及校验测试。真实MySQL集成测试通过`RUN_MYSQL_TESTS=true`显式启用，必须连接独立测试库，不能使用已有业务库。已经在本机13316隔离实例验证，结果见`docs/测试与实现说明.md`。

HTTP测试工具`scripts/http-smoke.py`会启用测试用户、创建测试档案、完成示例跟进并导出文件，只能针对一次性的测试库，需显式设置`ALLOW_DISPOSABLE_HTTP_TEST=true`。工具固定访问18080，不能用于正式服务。MySQL测试还会检查数据库名必须以`_test`或`_regression`结尾；普通测试事务回滚，数据库故障验证清理自己的探针记录，HTTP测试产生数据留在隔离测试库。

## 九 当前边界

真实备案短信平台、卫生服务中心同步、街道报表平台未提供凭据和协议，因此不伪造接入；SMS触达接口明确返回501。图片只支持PNG/JPEG，通过重新编码移除附加元数据，脱敏仍由上传者明确确认；没有声称已接入杀毒或自动识别签字内容的服务。

当前提供本人范围内的单条业务操作及持久异步生成、导出，未新增通用批量写接口。允许在老人档案专用加密字段登记姓名、电话、家庭住址及家属联系信息；不设身份证号字段，输入命中身份证号即拒绝。1.2.0通过社区选择与多社区授权控制业务范围，增加社区资料、图标和全局操作日志。管理员与统计员按岗位跨社区访问，网格员保持本人负责范围。

会话为单实例内存，重启后重新登录。队列租约为30分钟，当前每次生成及导出应在此窗口内完成；大规模文档或数据处理需要续租及独立工作进程。本版没有引入Redis或消息队列。历史任务期限和归属经过改期后，不支持任意过去时点完整重建所有跟进细项；正式上报以已冻结的快照文件为准。
