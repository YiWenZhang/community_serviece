# 社区老年服务系统

面向社区工作人员的老年服务管理系统，已实现登录与权限、老人档案、接种核验、通知触达、跟进任务、附件、统计报表、用户及规则管理，以及政策知识库、AI结构化解析和带原文引用的RAG问答。政策问答的配置、演示与使用见[政策问答启动说明](政策问答启动说明.md)。

当前架构：**Vue 前端 → Spring Boot 服务 → MySQL + 本地Lucene向量索引 → 配置的模型API**。后端按领域分包，统一打包；启动不需要 Nacos、Redis 或独立向量数据库进程。

## 技术栈

| 部分 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1.1、Spring Security、Spring JDBC、Maven |
| 前端 | Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、Axios |
| 数据库 | MySQL 8.0.16 及以上，31 张业务表与6张AI表；Lucene 9.12.2 |
| 接口与导出 | OpenAPI / Swagger UI、Apache POI（Excel、Word） |

## 队友首次运行（Windows / PowerShell）

先安装并配置好 Git、JDK 21、Maven 3.9、Node.js 22.12 或以上、MySQL 8。`git`、`java`、`mvn`、`node`、`npm.cmd` 应可在终端执行。MySQL 使用本机 `3306` 端口，下面的服务命令按服务名 `MySQL80` 编写；若安装时使用其他名称，需同步调整启动脚本中的服务名。

### 1. 克隆仓库

```powershell
git clone https://github.com/YiWenZhang/community_serviece.git
cd community_serviece
```

之后的相对路径均以克隆后的仓库根目录为准，不要求放到作者电脑的 `D:\Desktop\CummityService`。

### 2. 启动 MySQL 并首次导入

在管理员 PowerShell 中启动 MySQL，已运行可跳过：

```powershell
Start-Service MySQL80
```

回到仓库根目录，指定自己电脑上实际的 `mysql.exe` 路径：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\database\本机导入.ps1 -MySqlExe "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
```

按提示输入现有 MySQL `root` 密码，等待显示 `导入成功：community_elder_service`。使用其他已有建库账号时，可追加 `-MySqlUser 你的账号`。

脚本读取 `database/导入数据库.sql`，创建数据库、31 张业务表及少量虚构示例数据，**不需要提前手动创建数据库**。每台电脑只首次导入一次；数据库已存在或导入报错时，先检查原因，不要删除数据库或忽略错误重导。1.1.0后端首次启动会增量创建AI表；不会重建现有业务表。

### 3. 构建后端并安装前端依赖

在仓库根目录执行，任一步失败请先解决错误：

```powershell
Push-Location backend
mvn clean package
Pop-Location

Push-Location frontend
npm.cmd ci
Pop-Location
```

仓库不提交 JAR、`target`、`node_modules` 和 `dist`；每位开发者在本机生成。

### 4. 启动整个网站

在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\启动网站.ps1
```

脚本打开两个窗口，保留它们：

1. 后端窗口输入 **MySQL 密码**，再设置首次管理员的 **应用密码（12～72 个字符）**。已有管理员后，此项直接回车即可，不会重置密码。
2. 前端窗口等待显示 `Local`；后端窗口显示 `Started CommunityApplication` 后访问网站。

| 用途 | 地址 |
| --- | --- |
| 网站 | http://127.0.0.1:5173 |
| 后端健康检查 | http://127.0.0.1:8080/actuator/health |
| Swagger UI | http://127.0.0.1:8080/swagger-ui.html |

管理员账号为 `admin_demo`，密码为刚才自己设置的应用密码。进入“用户管理”，启用 `grid_demo`、`stats_demo` 并设置各自的密码，保留原角色。使用 `grid_demo` 体验示例老人档案、通知及跟进；`stats_demo` 查看统计和导出报表。管理员默认没有老人档案读取权限。

MySQL 账号与应用账号相互独立；DBeaver 连接 `127.0.0.1:3306`、库名 `community_elder_service`，使用 MySQL 账号和密码。开发时前端通过 Vite 代理访问后端，接口前缀为 `/api/v1`。

停止前后端：在对应窗口按 `Ctrl+C`；MySQL 可以继续运行。以后启动无需重复导入；修改 Java 后先重新构建并重启后端，修改 Vue 源码时 Vite 自动更新页面。

### 分别启动

在仓库根目录打开两个 PowerShell 终端：

```powershell
# 终端一：后端；自定义数据库账号可追加 -DbUser 你的账号
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\start-local.ps1
```

```powershell
# 终端二：前端
cd frontend
npm.cmd run dev
```

## 先看哪些文档

| 文档 | 内容 |
| --- | --- |
| [快速启动](快速启动.md) | 作者现有电脑的启动命令、常见问题；新克隆环境优先按本 README 操作 |
| [系统设计文档](社区老年服务系统设计文档_v1.0_20261016.md) | 业务设计、数据库设计与服务划分 |
| [原始项目文档](1.%20社区老年服务系统.docx) | 项目需求参考 |
| [数据库使用说明](database/数据库使用说明.md) | 初始化、DBeaver、表与示例数据说明 |
| [后端说明](backend/README.md) | 模块、配置、权限、业务规则、测试及部署 |
| [API 接口文档](backend/docs/API接口文档.md) | 请求参数、返回值、权限及错误处理 |
| [OpenAPI JSON](backend/docs/openapi.json) | 可导入接口调试工具的规范文件 |
| [前端说明](frontend/README.md) | 页面、接口代理与构建说明 |
| [协作约定](CONTRIBUTING.md) | 分支、提交、数据库变更与验证流程 |

## 目录

```text
backend/          Spring Boot 业务服务、测试与接口文档
frontend/         Vue 前端源码及依赖锁文件
database/         首次初始化 SQL、验证 SQL 与导入脚本
.github/          自动构建检查与 PR 模板
启动网站.ps1      Windows 本地启动入口
快速启动.md       本机启动说明
CONTRIBUTING.md   团队协作约定
```

## 协作与验证

从 `main` 拉取最新代码，为每项任务新建 `feat/功能名`、`fix/问题名` 或 `docs/主题` 分支，通过 Pull Request 合并。提交前运行：

```powershell
Push-Location backend
mvn test
Pop-Location

Push-Location frontend
npm.cmd run build
Pop-Location
```

GitHub Actions 同样执行后端测试与打包、前端依赖安装与构建。默认后端测试不连接数据库；真实 MySQL 集成测试需要独立测试库，具体见后端说明。自动构建不会启动完整网站，也不代表业务联调已完成。

密码、运行日志、上传文件、本机数据库和本地配置均不提交。`database/演示数据密钥.json` 专用于 SQL 中三条虚构编号的解密，是公开的开发样例，便于队友开箱验证；不要用于真实数据或生产环境。生产配置使用环境变量或密钥系统，参考后端配置说明。
