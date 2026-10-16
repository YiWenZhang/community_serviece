"""Read-only export from the local verification server; no database writes."""
from pathlib import Path
import json
import time
import urllib.request

for i in range(45):
    try:
        with urllib.request.urlopen('http://127.0.0.1:18080/v3/api-docs',timeout=5) as response:
            schema=json.load(response)
        break
    except Exception:
        time.sleep(1)
else:
    raise RuntimeError('OpenAPI server not ready')
assert not schema['paths']['/api/v1/auth/csrf']['get'].get('parameters'), 'CSRF对象不应作为请求参数'
schema['servers']=[{'url':'http://127.0.0.1:8080','description':'本机默认业务服务'}]
out=Path(__file__).resolve().parents[1]/'docs/openapi.json'
out.write_text(json.dumps(schema,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Latest packaged JAR OpenAPI exported:',sum(m in ['get','post','put','patch','delete'] for item in schema['paths'].values() for m in item),'operations')
