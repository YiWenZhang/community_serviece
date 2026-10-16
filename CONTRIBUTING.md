# 协作开发约定

## 分支与提交

首次运行按根目录 README 操作。每人使用自己的本机数据库与应用密码，不共享运行数据目录。

开始一项任务前：

```powershell
git switch main
git pull --ff-only origin main
git switch -c feat/elder-search
```

分支使用 `feat/`、`fix/`、`docs/` 前缀。每个分支只处理一项明确的需求；如已有未提交改动，先提交或妥善保存，再切换分支。提交信息建议使用 `feat: 增加老人查询条件`、`fix: 修复跟进状态判断`、`docs: 更新接口说明`。

提交并发起 Pull Request：

```powershell
git status
git diff
git add 需要提交的文件路径
git commit -m "feat: 增加老人查询条件"
git push -u origin feat/elder-search
```

在 GitHub 上创建指向 `main` 的 Pull Request，写清变化、验证结果、接口及数据库影响，请队友审查。不要强制推送公共 `main`，不要提交别人的未完成改动。团队有仓库管理权限后，建议配置 `main` 分支保护，要求 PR 审查和构建检查通过。

## 代码与接口

- 后端按现有 `cn.community` 下的业务包组织，继续维护社区范围、角色权限、事务、幂等和乐观锁约束。
- 前端继续使用 TypeScript、现有 API 层及页面结构，保持 Cookie / CSRF 登录流程。
- 改动接口时，同步更新 `backend/docs/API接口文档.md`、OpenAPI 文件及调用方；不要只改后端字段名。
- 保持现有代码风格与 UTF-8 编码，PowerShell 使用带 BOM 的 UTF-8，避免 Windows PowerShell 中文乱码。
- 不提交密码、真实老人信息、生产密钥、附件或导出文件。公开演示密钥只配合虚构样例使用。

## 数据库变更

`database/导入数据库.sql` 用于空环境首次初始化，不能作为已有环境升级脚本。需要调整表结构时，在 `database/migrations/` 新增按顺序命名的增量 SQL（例如 `V002__add_notice_index.sql`），在 PR 中说明适用版本、备份要求、执行顺序及回退方案，并同步维护首次初始化脚本与数据库文档。

当前未接入 Flyway / Liquibase；增量 SQL 不会自动执行，需按变更说明人工执行。不要删除已有数据或通过重复导入初始化 SQL 升级数据库。新增样例必须使用虚构数据。

## 提交前验证

在 `backend` 执行 `mvn test` 或 `mvn clean package`，在 `frontend` 执行 `npm.cmd ci`（锁文件有变动时）及 `npm.cmd run build`。涉及业务流程时，启动网站验证相应角色的操作与权限，并在 PR 中记录结果。

默认 Maven 测试不需要数据库。真实 MySQL 集成测试及 HTTP 验证工具只能运行于独立、可丢弃的测试库，不能指向本机日常开发库或生产库，配置方式见 `backend/README.md`。

GitHub Actions 会检查后端测试与打包、前端构建。依赖变更需提交前端锁文件或 Maven 配置；构建产物和依赖目录无需提交。
