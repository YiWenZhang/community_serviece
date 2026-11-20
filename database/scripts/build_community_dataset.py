"""Generate the versioned, privacy-preserving four-community initial data package."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'database' / 'initial-data'
OUT.mkdir(parents=True, exist_ok=True)

communities = [
    dict(id=1, code='ZHIYUAN', name='智园', color='#25776e', theme='智慧助老 · 邻里互助',
         grids=['智园北片网格', '智园南片网格'],
         description='智园以成熟居住区和退休职工家庭为主要服务场景，围绕“智慧助老、邻里互助”建设便民养老服务网络。社区设综合受理、午间助餐、数字助老课堂和邻里议事四类服务单元，重点回应独居老人用餐、线上办事和日常联系需求。北片网格负责常态探访与信息更新，南片网格衔接助餐登记和活动通知；跨社区网格员按本人负责档案提供补充服务。\n综合受理时间为工作日09:00—12:00、14:00—17:00；周二上午开设数字助老课堂，周四下午开展邻里交流。老人可线下提出申请，网格员登记需求、协助对接并回访；不以使用智能手机作为享受服务的前提。收费项目须事前告知并由服务机构公示，不承诺未经主管部门核定的补贴。社区坚持自愿参与、最少收集、责任到人，建立受理、评估、转介、回访的服务闭环。'),
    dict(id=2, code='HUIYUAN', name='慧园', color='#9b713f', theme='温暖助餐 · 家庭支持',
         grids=['慧园东片网格', '慧园西片网格'],
         description='慧园面向居家养老和家庭照护需求，形成“温暖助餐、家庭支持”的社区服务特色。社区重点提供助餐需求受理、照护资源转介、照护者交流和健康科普，优先了解高龄、独居及行动不便老人面临的实际困难。东片网格侧重常态需求联系，西片网格负责重点对象跟进与家庭支持，网格员之间共享事项进度但不越权读取对方负责档案。\n综合受理时间为工作日08:30—11:30、13:30—17:00；助餐需求在工作日上午集中核对，周三下午安排家庭照护交流，周五上午开展健康科普。送餐、照护及就医陪同事项由具备相应条件的服务提供者确认承接，社区负责联络与跟踪，不代替专业评估或医疗判断。对暂时无法满足的需求说明原因并提供其他可行渠道，回访时听取老人本人意见，持续改进服务质量。'),
    dict(id=3, code='ZHUOYUAN', name='卓园', color='#4c6ba2', theme='健康促进 · 活力银龄',
         grids=['卓园中心网格', '卓园书香网格'],
         description='卓园以退休后社会参与和健康生活支持为重点，建设“健康促进、活力银龄”的社区养老服务空间。社区设置银龄学堂、健康教育、文体活动和便民咨询服务单元，同时关注从活力养老向照护支持过渡的老人。中心网格负责健康活动联系与预约信息核对，书香网格衔接阅读、学习和志愿互助；跨社区责任人在授权范围内分别负责自己的老人。\n综合受理时间为工作日09:00—12:00、14:00—17:30；周一下午开展健康教育，周三上午安排阅读交流，周五下午组织适宜的文体活动。活动前说明强度、人数与场地要求，老人自主选择，健康风险和接种资格由医疗机构评估。对听力、出行或数字使用有困难的老人提供适配沟通方式，支持线下登记，避免以一次缺席判断无服务需求。'),
    dict(id=4, code='YUEYUAN', name='越园', color='#865e91', theme='安心探访 · 无障碍关怀',
         grids=['越园和邻网格', '越园安居网格'],
         description='越园以高龄、独居及出行不便老人的日常支持为重点，突出“安心探访、无障碍关怀”。社区通过关爱联系、居家安全提示、适老化改造需求转介和助餐支持，帮助老人维持熟悉环境中的生活。和邻网格负责日常联系与邻里互助，安居网格负责重点需求转介和事项回访；同一社区的不同网格员保持本人负责人范围，交接事项由管理员办理。\n综合受理时间为工作日08:30—12:00、14:00—17:00；周二、周四安排预约探访，周五下午汇总待办与回访情况。探访遵循本人意愿，先联系再上门，对未联系上、明确拒绝和紧急异常分别记录处置，不把家属接听或公告张贴等同于老人已接收。紧急问题优先联系相应应急救援渠道，社区不代替专业救援；一般事项落实承接人、办理进度和结果反馈。'),
]
users = [
    dict(id=1, username='admin', displayName='周明远', role='ADMIN', communities=[1,2,3,4], home=1),
    dict(id=2, username='stats', displayName='许文静', role='STATISTICIAN', communities=[1,2,3,4], home=1),
    dict(id=3, username='grid1', displayName='陈志强', role='GRID_WORKER', communities=[1], home=1),
    dict(id=4, username='grid2', displayName='李晓敏', role='GRID_WORKER', communities=[2], home=2),
    dict(id=5, username='grid3', displayName='王佳宁', role='GRID_WORKER', communities=[1,2], home=1),
    dict(id=6, username='grid4', displayName='赵文博', role='GRID_WORKER', communities=[3,4], home=3),
    dict(id=7, username='grid5', displayName='孙悦', role='GRID_WORKER', communities=[1,3,4], home=4),
]
needs = [('BIRTHDAY_CARE','生日关怀'),('VACCINE_REMINDER','疫苗接种提醒'),('HEALTH_LECTURE','健康讲座'),('MEAL_SUPPORT','助餐服务'),('HOME_VISIT','探访关爱'),('HOME_CARE','居家照护转介'),('DIGITAL_HELP','数字助老'),('ACCESSIBILITY','适老化需求转介'),('CULTURAL_ACTIVITY','文体与学习'),('CAREGIVER_SUPPORT','家庭照护支持')]
# Service profiles and all contact details below are synthetic. No identity-card data is generated.
profiles = [
 (1,3,0,'MALE','1947-11',[1,2,4,5],'独居，日常生活基本自理；工作日午餐需要助餐支持，愿意参加邻里活动；宜周二上午预约联系，必要时由女儿转告。','COMPLETED'),
 (1,3,0,'FEMALE','1955-04',[3,7,9],'与配偶共同生活，喜欢阅读；线上挂号和智能手机使用需要指导；愿意参加数字助老课堂，活动通知优先本人接收。','NOT_VACCINATED'),
 (1,5,1,'FEMALE','1942-09',[4,5,6],'高龄，与家人分开居住；短距离出行需陪伴，申请午间助餐和定期探访；女儿可协助转告，服务安排需提前约定。','UNKNOWN'),
 (1,5,1,'MALE','1958-01',[2,3,9],'与配偶居住，能够自主外出；关注冬季健康和预防接种信息；愿意参加适宜强度的集体活动，接种时间需与家人商议。','NOT_VACCINATED'),
 (1,7,1,'FEMALE','1951-07',[5,7,8],'居家生活基本自理；希望了解扶手、防滑和照明改善渠道；不熟悉线上申请，接受线下协助，周四下午适合回访。','INELIGIBLE'),
 (2,4,0,'FEMALE','1949-02',[1,4,5],'独居，能够自行到助餐点取餐；希望每周工作日午餐有稳定安排；愿意接受关爱联系，通知可请儿子协助转告。','COMPLETED'),
 (2,4,0,'MALE','1940-12',[4,6,10],'高龄，由配偶协助日常照料；备餐和家务负担较重，希望了解照护支持；接受家庭照护资源转介，不需要记录详细病史。','UNKNOWN'),
 (2,4,0,'FEMALE','1957-06',[3,9,10],'与家人共同居住，参与照护配偶；希望获得照护者交流及健康教育；周三下午能够参加，活动时长不宜过长。','NOT_VACCINATED'),
 (2,5,1,'MALE','1946-10',[4,5,8],'行动较慢，外出需使用辅助工具；有送餐与居家安全提示需求；服务前先联系本人，未联系上时请家属确认方便时间。','NOT_VACCINATED'),
 (2,5,1,'FEMALE','1953-03',[2,4,7],'与配偶居住，希望了解助餐菜单和预约流程；能够线下办理，线上操作需指导；接受非紧急事项由家属协助转告。','COMPLETED'),
 (3,6,0,'MALE','1956-05',[2,3,9],'退休后保持阅读和散步习惯，愿意参与志愿互助；关注接种服务流程，接种意愿需本人确认；偏好工作日上午联系。','NOT_VACCINATED'),
 (3,6,0,'FEMALE','1948-08',[1,3,5],'与配偶居住，参加活动需家人陪同；愿意接受健康科普和节日关怀；已选择固定联系时段，避免午休期间打扰。','COMPLETED'),
 (3,6,0,'MALE','1943-11',[5,6,8],'高龄，近距离出行需要协助；希望对接居家支持和无障碍服务资源；不愿参加集体文体活动，但接受个别探访。','INELIGIBLE'),
 (3,7,1,'FEMALE','1961-01',[3,7,9],'生活自理，喜欢书画和社区阅读；希望学习线上便民服务；可参与邻里互助，活动通知以本人意愿为准。','UNKNOWN'),
 (3,7,1,'MALE','1950-09',[2,4,9],'与家人分开居住，午餐偶有助餐需求；愿意参加健康教育和轻度文体活动；接种预约拟由儿子陪同办理。','NOT_VACCINATED'),
 (4,6,0,'FEMALE','1939-04',[4,5,6],'高龄独居，近亲定期照看；需要稳定关爱联系与午间助餐支持；上门前先约定，未联系上须按关爱规程跟进。','UNKNOWN'),
 (4,6,0,'MALE','1945-07',[5,8,10],'与配偶居住，家庭照护力量有限；关注居家防滑和出行便利，接受适老化需求转介；希望照护者共同参与沟通。','NOT_VACCINATED'),
 (4,7,1,'FEMALE','1952-12',[2,4,7],'生活基本自理，工作日有助餐需求；对数字支付和预约操作不熟悉；愿意由女儿协助转告，服务收费需先说明。','COMPLETED'),
 (4,7,1,'MALE','1941-03',[4,5,6],'高龄，日常活动以居家为主；愿意接受定期探访和照护资源介绍；家属工作日晚上方便联系，社区仅记录脱敏关系和转告结果。','INELIGIBLE'),
 (4,7,1,'FEMALE','1959-10',[1,3,9],'与配偶共同生活，能够自主参加活动；关注健康生活和文体学习；对接种服务持审慎态度，愿意先到接种机构咨询。','NOT_VACCINATED'),
]
elders = [dict(community=c,owner=o,gridIndex=g,sex=s,birthMonth=b,needs=n,remark=r,vaccine=v) for c,o,g,s,b,n,r,v in profiles]

names=['宋江','卢俊义','吴用','公孙胜','关胜','林冲','秦明','呼延灼','花荣','柴进','李应','朱仝','鲁智深','武松','董平','张清','孙二娘','扈三娘','顾大嫂','李逵']
for i,e in enumerate(elders):
    e['sex']='FEMALE' if names[i] in ['孙二娘','扈三娘','顾大嫂'] else 'MALE'
    community=communities[e['community']-1]['name']
    e['personalInfo']=dict(name=names[i],phone=f'1990000{i+1:04d}',address=f'{community}社区{i%3+1}号楼{i%2+1}单元{101+i}室',familyName=names[i][0]+'某',familyRelation='儿子' if i%2 else '女儿',familyPhone=f'1880000{i+1:04d}',familySmsAllowed=False)

manifest = dict(version='2026.11-v3', description='四社区业务初始数据；人员与档案为合成资料，公开政策为来源全文，内部规范为本项目编制。', communities=communities, users=users, serviceNeeds=[dict(id=i+1,code=c,name=n) for i,(c,n) in enumerate(needs)], elders=elders)
policy_catalog = OUT / 'policy-catalog.json'
if policy_catalog.exists():
    manifest['policies'] = json.loads(policy_catalog.read_text(encoding='utf-8'))
    for p in manifest['policies']:
        p['sha256'] = hashlib.sha256((ROOT/p['path']).read_bytes()).hexdigest()
(OUT/'communities.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print('Generated',len(communities),'communities,',len(users),'accounts,',len(elders),'elder profiles')
