# 政策问答接口（1.1.0）

所有接口前缀为 `/api/v1/ai`，沿用Cookie Session和CSRF。返回 `{data, traceId}`；BIGINT以字符串返回。社区、用户身份从会话读取，不能通过请求指定。模型密钥永不在读取接口中返回，错误不透传上游响应正文。

| 方法 | 路径 | 权限与用途 |
| --- | --- | --- |
| GET | `/providers` | `ADMIN`角色 + `system:config:manage` + `ai:model:manage`，配置列表 |
| POST | `/providers` | 同上，新增配置 |
| PUT | `/providers/{id}` | 同上，更新停用的配置 |
| DELETE | `/providers/{id}` | 同上，删除停用的配置 |
| POST | `/providers/{id}/test` | 同上，真实请求验证协议、密钥、JSON或向量输出 |
| POST | `/providers/{id}/activation` | 同上，`{active:true/false}`，同用途互斥切换 |
| GET | `/status` | `ai:chat`，当前模型、向量方式与可检索文档数量 |
| GET | `/documents?page=1&pageSize=20&q=` | `ai:knowledge:manage`，标题/文号检索及分页 |
| POST | `/documents` | 同上，multipart上传并异步解析 |
| GET | `/documents/{id}` | 同上，元数据、解析全文、结构化摘要和片段 |
| POST | `/documents/{id}/action` | 同上，`{action,version}`，action取retry/enable/disable/delete |
| GET | `/documents/{id}/content` | 管理员或具备`ai:chat`的用户下载私有原文件；问答用户仅可下载READY文档 |
| GET | `/conversations` | `ai:chat`，最近100个本人对话 |
| POST | `/conversations` | `ai:chat`，创建对话，返回id |
| DELETE | `/conversations/{id}` | `ai:chat`，删除本人且未在生成中的对话 |
| GET | `/conversations/{id}/messages` | `ai:chat`，本人对话消息与引用快照 |
| POST | `/conversations/{id}/messages` | `ai:chat`，RAG问答 |

## 模型配置字段

```json
{
  "name": "DeepSeek Flash",
  "kind": "CHAT",
  "apiFormat": "OPENAI",
  "baseUrl": "https://api.deepseek.com",
  "model": "deepseek-flash",
  "apiKey": "请填自己的密钥",
  "version": 0
}
```

`kind`可取CHAT或EMBEDDING，创建后不可更改。`apiFormat`可取OPENAI或ANTHROPIC，EMBEDDING仅支持OPENAI。更新时apiKey留空保留原密钥，必须提交当前version。基础地址支持HTTPS，本地测试可使用localhost或127.0.0.1的HTTP；拒绝含账号、查询参数、片段的地址。不跟随上游重定向。

## 文档上传字段

`Content-Type: multipart/form-data`。file为文件；title为必填标题；category取ELDERCARE/COMMUNITY/VACCINE/OTHER；issuer、policyNo为元数据；effectiveDate、expiryDate可选，格式YYYY-MM-DD；isDemo为演示标记，默认false。

支持PDF、DOCX、UTF-8 TXT/Markdown。单文件最多20MB，正文12万字符，PDF最多200页，最多160个原文片段。扫描PDF需先OCR。重复SHA-256的非删除文档拒绝上传。状态为PENDING、PROCESSING、READY、FAILED、DISABLED、DELETED；处理中拒绝修改，旧版本操作返回409，失败原因保存在errorSummary。

## 问答请求与引用

```json
{
  "question": "社区助餐申请需要哪些材料？",
  "requestId": "6a41b55a-7dbf-42bb-96c5-65f3d9969999"
}
```

question最多2000字符，requestId为每次新问题生成的UUID。同一对话相同requestId与相同问题重复发送，返回先前结果；改为不同问题会拒绝。单个对话同时只允许一个生成请求，失败自动解除锁。网络超时后可用原requestId重试，避免重复落库。

答复返回id、question、answer、model、citations。每个citation包含number、chunkId、documentId、title、issuer、policyNo、effectiveDate、expiryDate、isDemo、section、pageNo、startOffset、endOffset、content、safeName。content为提取原文的逐字切片，偏移按Java UTF-16字符索引，endOffset为不包含端点。PDF页码可用时返回；DOCX和文本不伪造物理页码。

检索只取本社区READY、当前有效且embeddingSpace匹配的文档，采用Lucene HNSW与CJK BM25的RRF融合。问题会结合最近3轮上下文供模型理解指代。模型输出必须包含answer、sourceIds和insufficient，所有引用编号由服务端验证。无依据可返回不带引用的说明；存在非法引用或回答无引用时返回502，不保存该答案。演示原文被引用时强制注明非真实政务依据。

## 错误

401未登录；403无权限或CSRF无效；404资源不存在、跨社区或非本人对话；409版本/幂等冲突；422文档/配置无效或资源忙；502模型调用、鉴权、输出格式或引用校验失败；503索引/检索不可用；504模型超时。单次上游HTTP请求超时120秒，前端问答允许等待180秒，文档解析在后台持续更新状态。
