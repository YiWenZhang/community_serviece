"""Only run against a disposable seeded test database; never enables production accounts."""
import urllib.request
import urllib.error
import http.cookiejar
import json
import time
import io
import zipfile
import os
from pathlib import Path

if os.environ.get('ALLOW_DISPOSABLE_HTTP_TEST')!='true':
    raise RuntimeError('仅限一次性测试库；确认后设置ALLOW_DISPOSABLE_HTTP_TEST=true')

BASE='http://127.0.0.1:18080'
class Client:
    def __init__(self):
        self.cookies=http.cookiejar.CookieJar()
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.token=None
    def call(self,method,path,body=None,key=None,expect=200,csrf=True):
        headers={'Content-Type':'application/json'}
        if self.token and csrf:headers['X-XSRF-TOKEN']=self.token
        if key:headers['Idempotency-Key']=key
        req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8'),headers=headers,method=method)
        try:
            with self.opener.open(req,timeout=30) as response:status=response.status;data=response.read();ctype=response.headers.get('Content-Type','')
        except urllib.error.HTTPError as response:status=response.code;data=response.read();ctype=response.headers.get('Content-Type','')
        assert status==expect,(method,path,status,data.decode('utf-8','replace')[:1200])
        if 'json' in ctype:return json.loads(data)
        return data
    def csrf(self):self.token=self.call('GET','/api/v1/auth/csrf')['data']['token']
    def login(self,user,password):
        self.csrf();self.call('POST','/api/v1/auth/login',{'username':user,'password':password});self.csrf()

checks=[]
anonymous=Client()
for i in range(60):
    try:
        if anonymous.call('GET','/actuator/health')['status']=='UP':break
    except Exception:time.sleep(1)
else:raise RuntimeError('HTTP service did not become ready')
anonymous.call('GET','/api/v1/elders',expect=401);checks.append('未登录返回401')
anonymous.call('POST','/api/v1/auth/login',{'username':'x','password':'x'},csrf=False,expect=403);checks.append('缺少CSRF返回403')
admin=Client();admin.login('admin_demo','Only-For-HTTP-Test-2026');checks.append('Cookie及CSRF登录成功')
admin.call('GET','/api/v1/elders',expect=403);checks.append('管理员不自动读取老人业务明细')
users=admin.call('GET','/api/v1/users')['data']['items']
for uid,username,role,name,password in [('2','grid_demo','2','演示网格员','Only-Grid-Test-2026'),('3','stats_demo','3','演示统计员','Only-Stats-Test-2026')]:
    row=next(u for u in users if u['id']==uid)
    admin.call('POST',f'/api/v1/users/{uid}/password',{'version':row['version'],'password':password})
    admin.call('PATCH',f'/api/v1/users/{uid}',{'version':int(row['version'])+1,'displayName':name,'status':'ACTIVE','roleIds':[role]})
grid=Client();grid.login('grid_demo','Only-Grid-Test-2026')
stats=Client();stats.login('stats_demo','Only-Stats-Test-2026')
rows=grid.call('GET','/api/v1/elders')['data']['items'];assert len(rows)==3
assert all('elderNo' in row and 'elderNoCipher' not in row for row in rows);checks.append('本人档案查询及编号解密，未泄露密文摘要')
stats.call('GET','/api/v1/elders',expect=403);checks.append('统计员无法读取老人明细')
draft={'gridId':'1','sex':'FEMALE','birthMonth':'1950-06','remark':'HTTP虚构测试','needIds':['1']}
newelder=grid.call('POST','/api/v1/elders',draft)['data'];assert newelder['elderNo'].startswith('SH-L-2026-');checks.append('HTTP登记老人及事务编号')
recipient=grid.call('GET','/api/v1/notice-recipients/2')['data']
task=grid.call('GET','/api/v1/followups/2')['data']
attempt={'version':recipient['version'],'channel':'FAMILY_RELAY','result':'CONFIRMED','familyNameMasked':'张某','familyRelation':'女儿','relayConfirmed':True,'feedbackQuote':'已经转告','fileIds':[]}
record={'version':task['version'],'outcome':'RESOLVED','resultText':'HTTP测试跟进确认','attempt':attempt}
one=grid.call('POST','/api/v1/followups/2/records',record,key='http-followup-1')['data']
two=grid.call('POST','/api/v1/followups/2/records',record,key='http-followup-1')['data'];assert one['id']==two['id'] and two['replayed'];checks.append('HTTP跟进完成及重复提交幂等')
metrics=stats.call('GET','/api/v1/reports/metrics?month=2026-10')['data'];assert metrics['metrics'];checks.append('HTTP按权限汇总统计')
for fmt,member in [('XLSX','xl/workbook.xml'),('DOCX','word/document.xml')]:
    job=stats.call('POST','/api/v1/report-jobs',{'query':{'month':'2026-10'},'format':fmt},key='http-report-'+fmt)['data']
    for i in range(30):
        row=stats.call('GET','/api/v1/report-jobs/'+job['reportJobId'])['data']
        if row['status']=='COMPLETED':break
        if row['status']=='FAILED':raise AssertionError(row)
        time.sleep(1)
    else:raise AssertionError(('report not completed',row))
    raw=stats.call('GET','/api/v1/files/'+row['outputFileId']+'/content')
    with zipfile.ZipFile(io.BytesIO(raw)) as z:assert member in z.namelist()
    checks.append(fmt+'持久异步导出及鉴权下载结构有效')
schema=anonymous.call('GET','/v3/api-docs')
schema['servers']=[{'url':'http://127.0.0.1:8080','description':'本机默认业务服务'}]
out=Path(__file__).resolve().parents[1]/'docs'
out.mkdir(exist_ok=True)
(out/'openapi.json').write_text(json.dumps(schema,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert '/api/v1/elders' in schema['paths'];assert b'<html' in anonymous.call('GET','/swagger-ui/index.html').lower();checks.append('OpenAPI及Swagger UI真实HTTP可访问')
grid.call('POST','/api/v1/auth/logout',{});grid.call('GET','/api/v1/elders',expect=401);checks.append('注销后会话失效')
(out/'HTTP测试结果.md').write_text('# HTTP端到端测试记录\n\n测试对象：隔离测试数据库上的打包JAR，端口18080；未访问用户3306项目数据库。\n\n'+'\n'.join('- 通过：'+c for c in checks)+'\n',encoding='utf-8')
print('PASS',len(checks),'HTTP checks;',len(schema['paths']),'OpenAPI paths')
