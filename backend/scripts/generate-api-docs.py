"""Generate detailed Markdown contract from the actual runtime OpenAPI snapshot."""
from pathlib import Path
import json
import re

base=Path(__file__).resolve().parents[1]
doc=json.loads((base/'docs/openapi.json').read_text(encoding='utf-8'))
schemas=doc['components']['schemas']
def typename(schema):
    if '$ref' in schema:return schema['$ref'].split('/')[-1]
    if schema.get('type')=='array':return 'array<'+typename(schema.get('items',{}))+'>'
    return str(schema.get('type','object'))+(' ('+schema['format']+')' if 'format' in schema else '')
def constraints(schema):
    vals=[]
    for key in ['pattern','minimum','maximum','minLength','maxLength','minItems','maxItems','default']:
        if key in schema:vals.append(key+'='+str(schema[key]))
    if 'enum' in schema:vals.append('enum='+','.join(map(str,schema['enum'])))
    if schema.get('uniqueItems'):vals.append('元素去重')
    return '; '.join(vals).replace('|','或') or '—'

description={
'version':'当前资源版本；先GET最新值，旧版本409；保持JSON整数',
'revision':'反馈当前版本，首次0，更正时传最新revisionNo',
'gridId':'本社区有效网格ID', 'ownerUserId':'本社区有效网格员ID',
'birthMonth':'出生年月YYYY-MM，不包含出生日期；不得为未来月份',
'needIds':'有效服务需求ID数组；无需求时传[]',
'elderIds':'本人负责且启用的老人ID集合，最多1000人',
'campaignId':'具体年度与阶段的疫苗服务周期；疫苗通知和规则必填',
'periodKey':'通知的业务周期，例如2026-10',
'period':'自动生成目标月份YYYY-MM',
'leadDays':'提前准备天数，0至30；生日规则用于选择目标月份',
'familyNameMasked':'仅脱敏称谓，必须含某或*，例如张某',
'familyRelation':'家属关系，例如女儿',
'relayConfirmed':'家属明确确认已转告；家属渠道确认接收必须true',
'fileIds':'已由本人上传并确认脱敏的附件ID集合；无附件传[]',
'feedbackQuote':'客观反馈原话，但需移除禁采信息',
'reasonText':'拒绝或其他未联系原因必填，客观描述',
'unreachableReason':'NO_ONE_HOME、NO_ANSWER或OTHER；未联系上必填',
'channel':'HOME_VISIT、FAMILY_RELAY、BULLETIN；SMS本版返回501',
'result':'POSTED、CONFIRMED、UNREACHABLE或REFUSED；须匹配渠道',
'rating':'SATISFIED、NEUTRAL、DISSATISFIED',
'respondentType':'ELDER或FAMILY',
'appointmentDate':'授权人员确认的预约日期，可为空',
'vaccinatedOn':'接种日期；PARTIAL或COMPLETED时必填，不能为未来',
'doseNo':'实际针次，接种事实必填且至少1',
'eligibility':'ELIGIBLE、INELIGIBLE、UNKNOWN，未知不进接种率分母',
'vaccinationStatus':'NOT_VACCINATED、PARTIAL、COMPLETED、UNKNOWN',
'nextDueAt':'再次跟进期限，含时区；RETRY_REQUIRED必须在未来',
'dueAt':'任务截止时间，ISO8601含时区，例如2026-10-20T09:00:00+08:00',
'outcome':'RESOLVED、RETRY_REQUIRED、REFUSED、NO_LONGER_APPLICABLE',
'attempt':'本次触达对象；重新联系和接种前提醒解决时需确认触达',
'resultText':'跟进执行结果，不能只点完成而无说明',
'month':'统计月份YYYY-MM，边界使用Asia/Shanghai',
'ownerId':'统计经办人；社区统计可指定或为空，本人统计不可指定他人',
'noticeType':'可选VACCINE、BIRTHDAY、LECTURE、OTHER',
'format':'XLSX或DOCX',
'query':'月报查询条件，结构见Query',
'redactionConfirmed':'上传前必须true，表示文档/图片已人工确认脱敏',
'password':'应用登录密码；创建/管理员重置固定123456，可省略；不是MySQL密码',
'username':'应用登录账号，不使用手机号',
'currentPassword':'本人当前密码，修改前验证',
'newPassword':'至少6个字符且最多72个UTF-8字节，显示强度；不强制12位',
'confirmPassword':'必须与newPassword一致',
'roleIds':'有效角色ID集合，管理员1、网格员2、统计员3为原样例ID',
'permissionIds':'有效权限ID集合，不能移除管理员的用户及角色管理权限',
'ruleId':'本社区已启用通知规则ID',
'type':'具体枚举以pattern为准；通知类型或跟进类型，不能混用',
'remark':'业务备注；联系方式填写到专用加密字段，不在此明文保存',
'personalInfo':'必填加密资料对象：姓名必填；本人电话、住址、家属姓名、关系和电话可选；禁止身份证号',
'name':'老人姓名（personalInfo内）；列表查询时为姓名精确匹配',
'phone':'老人本人手机号或带区号固话，支持+86；列表查询时为规范化后的精确匹配',
'address':'家庭住址，专用加密字段，最长200字符',
'familyName':'家属姓名或称呼，专用加密字段',
'familyPhone':'家属电话，专用加密字段',
'reason':'移交、取消、更正或核验的客观说明',
}

lines=['# 社区老年服务业务微服务接口文档','',
'版本'+doc['info']['version']+'，基于实际运行服务导出的OpenAPI生成。含多社区、AI政策问答及密码安全接口。'
,'安全与启动详见[密码与安全检查说明](../../密码与安全检查说明.md)，权限范围详见[多社区权限接口](多社区权限接口.md)。',
'','## 一 调用约定','',
'默认HTTPS服务地址`https://127.0.0.1:8443`，接口前缀`/api/v1`。请求和响应UTF-8，业务JSON请求使用`Content-Type: application/json`。所有接口当前成功状态为200；异步接口通过响应中的作业ID表示排队，不代表已经完成。',
'',
'使用JSESSIONID Cookie保持会话。登录前先GET`/auth/csrf`，将响应data.token作为`X-XSRF-TOKEN`头用于登录。登录后重新GET一次csrf；每次POST、PUT、PATCH、DELETE均携带Cookie和该头。过期会话返回401。',
'',
'管理员可管理全部社区与老人；网格员仅操作获授权社区内本人负责档案，统计员仅能查看汇总与报表。X-Community-Id每次由后端验证；指定负责人、移交及统计筛选均按角色和社区核验，不信任客户端传参。越权对象404；缺少功能权限403。',
'',
'响应BIGINT主键、Long统计数量和total统一为字符串，避免JavaScript精度丢失；version、page、pageSize为整数，比例value为0到1的小数。SQL列名输出转换为camelCase。日期为YYYY-MM-DD，数据库时间为UTC，响应时间为ISO8601；展示请转上海时区。',
'',
'```json','{"data":{"id":"4","version":0},"traceId":"请求链路UUID"}','```','',
'分页data为`{items,total,page,pageSize}`，page从1开始，pageSize默认为20、最大100。详情和列表不返回passwordHash、编号密文、HMAC摘要或外部联系人令牌。',
'',
'| 状态 | 含义 |','| --- | --- |','| 400 | JSON、字段格式或校验失败 |','| 401 | 未登录、会话失效、账号停用或登录失败 |','| 403 | 功能权限不足或CSRF无效 |','| 404 | 资源不存在或不在本人范围 |','| 409 | version冲突、唯一键或幂等内容冲突 |','| 422 | 状态、关联或业务条件不满足 |','| 426 | HTTPS强制模式拒绝明文请求 |','| 429 | 当前密码验证暂时锁定 |','| 500 | 未预期服务错误，仅返回脱敏信息 |','| 501 | 短信等真实外部集成未接入 |','',
'错误形式为`{code,message,traceId}`；Security过滤链拒绝时至少有code与message，并通过响应头`X-Trace-Id`提供链路编号。','',
'## 二 权限与接口索引','',
'原样例角色ADMIN、GRID_WORKER、STATISTICIAN；权限配置以数据库为准。系统参数及用户角色由管理员维护，老人及服务由网格员维护，社区汇总由统计员查看。修改用户密码、角色和状态后旧会话立即失效，需重新登录。','',
'| 方法 | 路径 | 功能 |','| --- | --- | --- |']

operations=[]
for path,item in sorted(doc['paths'].items()):
    for method,op in item.items():
        if method in ['get','post','put','patch','delete']:
            operations.append((path,method,op))
            lines.append('| '+method.upper()+' | `'+path+'` | '+op.get('summary','')+' |')
lines+=['','共'+str(len(operations))+'个HTTP操作。下节逐项列出路径、查询、请求头及请求体；结构类型在第四节展开。','', '## 三 逐接口契约','']
for n,(path,method,op) in enumerate(operations,1):
    lines+=['### '+str(n)+' '+op.get('summary',path),'','`'+method.upper()+' '+path+'`','']
    params=op.get('parameters',[])
    if params:
        lines+=['| 参数 | 位置 | 必填 | 类型 | 约束 |','| --- | --- | --- | --- | --- |']
        for p in params:
            lines.append('| '+p['name']+' | '+p['in']+' | '+('是' if p.get('required') else '否')+' | '+typename(p.get('schema',{}))+' | '+constraints(p.get('schema',{}))+' |')
        lines.append('')
    body=op.get('requestBody',{}).get('content',{})
    if body:
        for mime,definition in body.items():
            lines+=['请求体：`'+mime+'`，结构`'+typename(definition.get('schema',{}))+'`。','']
    if path.endswith('/attempts') or path.endswith('/records') or path in ['/api/v1/report-jobs','/api/v1/notice-generations']:
        lines+=['必须提供`Idempotency-Key`，1至60位英文、数字或`_.:-`。同键同内容返回原记录或作业；同键不同内容409。触达和跟进重试可沿用第一次的version，服务端先判断幂等重放。','']
    if method!='get':lines+=['写请求必须有有效CSRF头；除幂等接口外，创建失败重试前先查询结果，不能假设所有POST都支持幂等。','']
    if path=='/api/v1/auth/csrf':lines+=['无登录要求。返回data.headerName及data.token；请求参数不需要填写CsrfToken对象。','']
    elif path=='/api/v1/auth/login':lines+=['无登录要求，但要求CSRF。返回当前账号id、username、communityId、roles和permissions。连续错误达到阈值会暂时锁定；错误不区分账号不存在和密码错误。','']
    elif path.endswith('/content'):lines+=['成功返回文件二进制流和Content-Disposition，不使用data包装。尚未关联的附件仅上传者可读，关联后按当前负责人的业务权限读取，原上传者移交后失去读取权；报表需申请人且仍具有对应范围导出权限，过期文件不可下载。','']
    elif path=='/api/v1/files' and method=='post':lines+=['multipart字段`file`和`redactionConfirmed=true`，仅有效JPEG或PNG，最多20MB及2500万像素，后端重编码为PNG移除原元数据；返回id、TEMP和PENDING，不能直接用于确认凭证。','']
    elif path.endswith('/finalize'):lines+=['上传者明确确认脱敏后返回id与ACTIVE；不代表系统已自动识别所有隐私或接入第三方杀毒。','']
    elif path=='/api/v1/report-jobs' and method=='post':lines+=['返回data.reportJobId和data.jobId。轮询GET /jobs/{jobId}或GET /report-jobs/{reportJobId}，完成后用outputFileId下载。','']
    elif path=='/api/v1/notice-generations':lines+=['返回data.id作业编号；生成范围为当前网格员本人。规则需由管理员先启用。','']
    elif path.endswith('/reports/metrics'):lines+=['返回month、ownerId、metricVersion、snapshotAt、cutoffAt及metrics数组。每个指标含code、dimension、numerator、denominator、value、nullReason。通知与反馈按事件或版本截止读取，疫苗按历史核验读取。','']
    elif path.endswith('/reopen'):lines+=['保留原触达和事件，重开接收对象并将批次恢复ACTIVE。必须说明原因，不能修改原反馈或伪造成功。','']
    elif path.endswith('/vaccines'):lines+=['当前状态及核验历史同事务；未知资格不计分母。已接种需日期和针次，已有接种状态的更正追加CORRECTION历史。','']
    else:lines+=['响应为data及traceId；创建返回内部id，修改通常返回id及新version，列表和详情按当前用户范围过滤。字段含义与业务示例见后文。','']

lines+=['## 四 请求对象字段字典','', '以下必填项以实际OpenAPI的required为准。ID请求可用数字或十进制字符串，建议前端统一字符串。可选字段省略或为null；空集合请显式传[]。','']
for name,schema in sorted(schemas.items()):
    if name=='CsrfToken':continue
    lines+=['### '+name,'','| 字段 | 类型 | 必填 | 规则与说明 |','| --- | --- | --- | --- |']
    required=schema.get('required',[])
    for field,definition in schema.get('properties',{}).items():
        desc=description.get(field,definition.get('description',''))
        limit=constraints(definition)
        lines.append('| '+field+' | '+typename(definition)+' | '+('是' if field in required else '否')+' | '+(desc+'；' if desc else '')+limit+' |')
    lines.append('')
lines+=['## 五 核心调用示例','',
'下列路径都相对于/api/v1；Cookie与CSRF省略展示但实际调用不可省略。样例ID以当前数据库查询为准，不要把文档中的ID当作永远固定。','',
'### 首次启用网格员','',
'管理员GET /users获得当前version。按需POST /users/2/reset-password重置为123456，再PATCH /users/2启用；每次写入version都会递增，不能在两步里重复使用旧版本。','',
'```json','{"version":0}','```','',
'```json','{"version":1,"displayName":"演示网格员","status":"ACTIVE","roleIds":["2"],"communityIds":["1"]}','```','',
'### 登记老人','',
'POST /elders：','',
'```json',json.dumps({'gridId':'1','sex':'FEMALE','birthMonth':'1950-06','remark':'虚构演示档案','needIds':['1','2']},ensure_ascii=False,indent=2),'```','',
'响应示例为`{"data":{"id":"4","elderNo":"SH-L-2026-004","version":0},"traceId":"..."}`，编号按实际序列分配；详情和列表向授权人员返回name、phone、address、familyName、familyRelation、familyPhone，不返回任何密文或HMAC。姓名和联系资料仅在personalInfo专用字段提交后加密保存；禁止身份证号。','',
'PATCH /elders/{id}采用`{"version":0,"profile":{...ElderDraft字段...},"status":"ACTIVE"}`，属于明确的整份业务资料替换，不是任意字段穿透修改。','',
'### 创建并激活通知','',
'POST /notices：','',
'```json',json.dumps({'type':'BIRTHDAY','title':'十月生日关怀','content':'请经办人开展生日月份关怀。','elderIds':['1'],'periodKey':'2026-10'},ensure_ascii=False,indent=2),'```','',
'取得id后POST /notices/{id}/activate，传`{"version":0}`。激活后接收对象version会递增，登记触达前应GET最新接收对象。VACCINE类型必须有campaignId；已预约时生成接种前3天提醒，未知预约时生成核实任务。','',
'### 家属转告及确认接收','',
'POST /notice-recipients/{id}/attempts，头`Idempotency-Key: visit-20261016-001`：','',
'```json',json.dumps({'version':1,'channel':'FAMILY_RELAY','result':'CONFIRMED','familyNameMasked':'张某','familyRelation':'女儿','relayConfirmed':True,'feedbackQuote':'已经转告，同意参加','fileIds':[]},ensure_ascii=False,indent=2),'```','',
'上门无人使用channel=HOME_VISIT、result=UNREACHABLE、unreachableReason=NO_ONE_HOME；reasonText记录客观原因。上门确认接收必须先上传图片、确认脱敏，再把文件id放fileIds。公告栏用BULLETIN加POSTED及照片，不认定个人接收。SMS渠道本版501。','',
'### 完成跟进','',
'先GET /followups/{id}和对应通知对象最新version，再POST /followups/{id}/records，头`Idempotency-Key: followup-001`：','',
'```json',json.dumps({'version':0,'outcome':'RESOLVED','resultText':'家属明确确认已转告','attempt':{'version':0,'channel':'FAMILY_RELAY','result':'CONFIRMED','familyNameMasked':'张某','familyRelation':'女儿','relayConfirmed':True,'feedbackQuote':'已转告','fileIds':[]}},ensure_ascii=False,indent=2),'```','',
'旧任务先结束，本次触达与后续任务同事务提交；任一失败全部回滚。RETRY_REQUIRED需未来nextDueAt；NO_LONGER_APPLICABLE需说明不再适用原因，关闭不计作接收成功。核实预约任务解决前须先登记真实预约日期，接种前提醒任务解决须包含确认触达。','',
'### 满意度与报表','',
'首次POST /notice-recipients/{id}/feedback：`{"revision":0,"rating":"SATISFIED","respondentType":"FAMILY","text":"服务说明清楚"}`；更正时传当前最高revisionNo，新增一条版本，不覆盖旧评价。','',
'GET /reports/metrics?month=2026-10；统计员默认社区汇总，网格员默认本人。POST /report-jobs：','',
'```json',json.dumps({'query':{'month':'2026-10'},'format':'XLSX'},ensure_ascii=False,indent=2),'```','',
'加Idempotency-Key。改format=DOCX可导出Word。拿reportJobId轮询，status=COMPLETED后GET /files/{outputFileId}/content下载。输出只有汇总，无隐藏个人明细。','',
'## 六 状态与统计口径','',
'通知批次：DRAFT→ACTIVE→COMPLETED或CANCELLED。接收状态：PENDING、CONFIRMED、UNREACHABLE、REFUSED。业务状态：OPEN、FOLLOWING、CLOSED、CANCELLED。拒绝及重试耗尽可形成闭环，但不增加已确认接收人数。','',
'跟进：PENDING、PROCESSING、COMPLETED、CANCELLED。逾期为未结束且dueAt早于当前时间，不覆盖原状态。默认次日重试，最多3次；达到上限应由经办人说明原因终结，不能自动记成功。','',
'通知覆盖率按接收对象去重计数；满意度分母只有有效反馈，同时显示反馈率；接种率按周期及已核验符合资格的人数计算；渠道使用率按实际触达尝试次数；分母0返回null及说明。月度边界采用上海时区半开区间。','',
'## 七 接入限制与微服务边界','',
'未接入真实短信、卫生数据或街道平台。没有AI接口，也没有向未来AI服务开放任意老人数据查询。当前账号与会话由本业务服务管理，未来AI鉴权需另行设计统一身份或可信令牌交换。','',
'当前使用31张已有表，无新增建表迁移。代码中没有通用任意表CRUD、原始SQL执行接口、默认通用管理员密码或匿名注册。详细运行、测试和当前边界见backend/README.md。','']
(base/'docs/API接口文档.md').write_text('\n'.join(lines),encoding='utf-8')
print('Generated documentation for',len(operations),'operations and',sum(n!='CsrfToken' for n in schemas),'request schemas')
