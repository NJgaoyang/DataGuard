#!/usr/bin/env python3
"""Isolated P0 DataGuard live demo provisioning + HTTPS/MCP approval acceptance.

Requires root on the development VM. Never prints passwords or Agent tokens.
Only creates dg_p0_demo databases, dg_p0_demo_* accounts and demo platform records.
The existing admin account and existing production/application databases are untouched.
"""
import base64
import json
import os
import pathlib
import secrets
import subprocess
import sys
import tempfile
import time
import uuid
from urllib.parse import urljoin
import requests

WORK=pathlib.Path('/etc/dataguard-new/p0-demo')
APP='https://110.42.132.44'
WORK.mkdir(parents=True,exist_ok=True)
WORK.chmod(0o700)
CREDS=WORK/'credentials.json'
STATE=WORK/'state.json'
REPORT=pathlib.Path('/opt/dataguard/docs/P0_LIVE_ACCEPTANCE.md')

def run(args, input=None, timeout=60):
 p=subprocess.run(args,input=input,text=True,capture_output=True,timeout=timeout)
 if p.returncode:
  # Do not print raw output: database tools may echo credentials.
  raise RuntimeError(f"Command failed: {args[0]} status={p.returncode}; inspect locally")
 return p.stdout

def write_private(path,obj):
 tmp=path.with_name(path.name+'.tmp')
 with tmp.open('x') as f:json.dump(obj,f,ensure_ascii=False,indent=2)
 tmp.chmod(0o600)
 os.replace(tmp,path)

def password():
 return 'Aa1!'+secrets.token_urlsafe(23)

if CREDS.exists():
 creds=json.loads(CREDS.read_text())
else:
 creds={k:password() for k in ('setup_admin','owner','reviewer','owner_changed','reviewer_changed','mysql_ro','starrocks_ro')}
 write_private(CREDS,creds)
state=json.loads(STATE.read_text()) if STATE.exists() else {}

def state_save():write_private(STATE,state)

def mysql(sql):
 return run(['docker','exec','-i','dataguard-mysql-test','sh','-c',
  'MYSQL_PWD="$(cat /run/secrets/mysql-root-password)" exec mysql -uroot -N -B'],sql)

def starrocks(sql):
 return run(['docker','exec','-i','dg-v2-starrocks-dev','mysql',
  '-uroot','-h127.0.0.1','-P9030','-N','-B'],sql,timeout=90)

def get_bcrypt(raw):
 with tempfile.TemporaryDirectory(dir=str(WORK)) as d:
  lib=pathlib.Path(d)/'crypto.jar'
  subprocess.run(['unzip','-p','/opt/dataguard/app.jar','BOOT-INF/lib/spring-security-crypto-6.5.6.jar'],
    stdout=lib.open('wb'),check=True,timeout=20)
  jcl=pathlib.Path(d)/'jcl.jar'
  with jcl.open('wb') as output:
   subprocess.run(['unzip','-p','/opt/dataguard/app.jar','BOOT-INF/lib/spring-jcl-6.2.12.jar'],
      stdout=output,check=True,timeout=20)
  classpath=d+':'+str(lib)+':'+str(jcl)
  j=pathlib.Path(d)/'HashPassword.java'
  j.write_text("""
import java.util.Scanner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
class HashPassword {
 public static void main(String[] args) {
  try(var scan=new Scanner(System.in)) {
   System.out.print(new BCryptPasswordEncoder(12).encode(scan.nextLine()));
  }
 }
}
""")
  run(['/opt/dataguard-toolchain/java21/bin/javac','-cp',str(lib),str(j)])
  return run(['/opt/dataguard-toolchain/java21/bin/java','-cp',classpath,'HashPassword'],raw+'\n').strip()

def bootstrap_db():
 mysql("""CREATE DATABASE IF NOT EXISTS dg_p0_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS dg_p0_demo.orders (
 order_id INT PRIMARY KEY, amount INT NOT NULL, customer_note VARCHAR(80) NOT NULL
);
INSERT IGNORE INTO dg_p0_demo.orders(order_id,amount,customer_note) VALUES
 (101,245,'demo-only-a'),(102,510,'demo-only-b'),(103,99,'demo-only-c');
CREATE USER IF NOT EXISTS 'dg_p0_demo_ro'@'%%' IDENTIFIED BY '%s' REQUIRE SSL;
ALTER USER 'dg_p0_demo_ro'@'%%' IDENTIFIED BY '%s' REQUIRE SSL;
GRANT SELECT ON dg_p0_demo.orders TO 'dg_p0_demo_ro'@'%%';
"""%(creds['mysql_ro'],creds['mysql_ro']))
 count=mysql("SELECT COUNT(*) FROM dg_p0_demo.orders;").strip()
 assert count=='3',f"MySQL sample count {count}"
 print('MYSQL: isolated demo DB and SSL-only SELECT account verified')
 starrocks("""CREATE DATABASE IF NOT EXISTS dg_p0_demo;""")
 starrocks("""CREATE TABLE IF NOT EXISTS dg_p0_demo.orders (
 order_id INT NOT NULL, amount INT NOT NULL, customer_note VARCHAR(80)
 ) ENGINE=OLAP DUPLICATE KEY(order_id)
 DISTRIBUTED BY HASH(order_id) BUCKETS 1
 PROPERTIES ("replication_num"="1");""")
 star_count=starrocks('SELECT COUNT(*) FROM dg_p0_demo.orders;').strip()
 if star_count=='0':
  starrocks("""INSERT INTO dg_p0_demo.orders VALUES
 (201,870,'starrocks-demo-a'),(202,420,'starrocks-demo-b'),(203,200,'starrocks-demo-c');""")
 assert starrocks('SELECT COUNT(*) FROM dg_p0_demo.orders;').strip()=='3'
 starrocks("""CREATE USER IF NOT EXISTS 'dg_p0_demo_ro'@'%%' IDENTIFIED BY '%s';
GRANT SELECT ON TABLE dg_p0_demo.orders TO USER 'dg_p0_demo_ro'@'%%';"""%(creds['starrocks_ro']))
 print('STARROCKS: isolated demo DB and table-level SELECT account verified')

def seed_temporary_admin():
 name='p0_demo_setup'
 hash_=get_bcrypt(creds['setup_admin'])
 sql="""INSERT INTO dataguard_v1.dg_users(id,username,display_name,password_hash,role_code,enabled,must_change_password)
 VALUES('%s','%s','P0 演示初始化','%s','ADMIN',1,0)
 ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),enabled=1,must_change_password=0;"""%(state.setdefault('setup_user_id',str(uuid.uuid4())),name,hash_)
 mysql(sql)
 state_save()

def disable_temporary_admin():
 mysql("UPDATE dataguard_v1.dg_users SET enabled=0 WHERE username='p0_demo_setup';")
 print('SECURITY: temporary provisioning administrator DISABLED')

class Client:
 def __init__(self):
  self.s=requests.Session()
  self.csrf=None
 def login(self,name,password):
  r=self.s.get(APP+'/api/csrf',timeout=12);r.raise_for_status()
  self.csrf=r.json()['token']
  r=self.s.post(APP+'/login',data={'username':name,'password':password},
    headers={'X-CSRF-TOKEN':self.csrf},timeout=12)
  assert r.status_code==200,f'Login failed for {name}: {r.status_code}'
  self.refresh()
  return self.req('GET','/api/session')
 def refresh(self):
  r=self.s.get(APP+'/api/csrf',timeout=12);r.raise_for_status()
  self.csrf=r.json()['token']
 def req(self,verb,url,data=None,expect=(200,)):
  r=self.s.request(verb,APP+url,headers={'X-CSRF-TOKEN':self.csrf},
    json=data,timeout=18)
  if r.status_code not in expect:
   raise RuntimeError(f'Unexpected {verb} {url} HTTP {r.status_code}: {r.text[:280]}')
  return r.json() if r.text else {}

def create_demo_user(cli,username,role,password_):
 users=cli.req('GET','/api/users')
 for user in users:
  if user['username']==username:return user['id']
 obj=cli.req('POST','/api/users',{'username':username,'displayName':'P0 '+username,
   'roleCode':role,'password':password_})
 return obj['id']

def ensure_user_ready(username,initial_key,changed_key):
 user=Client()
 if state.get(username+'_changed'):
  details=user.login(username,creds[changed_key])
  assert not details['mustChangePassword']
  return user
 details=user.login(username,creds[initial_key])
 if details['mustChangePassword']:
  r=user.req('POST','/api/password',
   {'currentPassword':creds[initial_key],'newPassword':creds[changed_key]})
  assert r['reauthenticate']
  state[username+'_changed']=True;state_save()
  user=Client();details=user.login(username,creds[changed_key])
 assert not details['mustChangePassword']
 return user

def get_or_create(items,key,name,create):
 existing=next((r for r in items if r.get('name')==name),None)
 if existing:return existing['id']
 r=create();return r['id']

def mcp(token,method,params=None):
 rpc={'jsonrpc':'2.0','id':str(uuid.uuid4()),'method':method}
 if params is not None:rpc['params']=params
 r=requests.post(APP+'/mcp',json=rpc,headers={'Authorization':'Bearer '+token,
   'MCP-Protocol-Version':'2025-11-25','Accept':'application/json'},timeout=22)
 assert r.status_code==200,f'MCP HTTP failure {r.status_code}'
 body=r.json()
 assert body.get('jsonrpc')=='2.0'
 return body

def call(token,tool,args):
 result=mcp(token,'tools/call',{'name':tool,'arguments':args})
 if 'error' in result:return {'error':result['error']}
 content=result['result']['content']
 return json.loads(content[0]['text'])

def provision_and_validate():
 cli=Client();details=cli.login('p0_demo_setup',creds['setup_admin'])
 assert details['roleCode']=='ADMIN'
 owner_id=create_demo_user(cli,'p0_demo_owner','READER',creds['owner'])
 reviewer_id=create_demo_user(cli,'p0_demo_reviewer','APPROVER',creds['reviewer'])
 owner=ensure_user_ready('p0_demo_owner','owner','owner_changed')
 reviewer=ensure_user_ready('p0_demo_reviewer','reviewer','reviewer_changed')
 assert owner.req('GET','/api/session')['roleCode']=='READER'
 assert reviewer.req('GET','/api/session')['roleCode']=='APPROVER'
 print('USERS: distinct active requester + real approver can sign in')

 data=[
  {'key':'mysql','name':'P0 演示 MySQL','type':'MYSQL','port':3307,'databaseName':'dg_p0_demo','password':creds['mysql_ro']},
  {'key':'starrocks','name':'P0 演示 StarRocks','type':'STARROCKS','port':9030,'databaseName':'dg_p0_demo','password':creds['starrocks_ro']}
 ]
 sources=cli.req('GET','/api/sources')
 for ent in data:
  source_id=get_or_create(sources,'id',ent['name'],lambda:cli.req('POST','/api/sources',{
   'name':ent['name'],'type':ent['type'],'host':'127.0.0.1',
   'port':ent['port'],'databaseName':ent['databaseName'],'username':'dg_p0_demo_ro',
   'password':ent['password']}))
  ent['sourceId']=source_id
  if not any(r['id']==source_id and bool(r['enabled']) for r in sources):
   cli.req('POST',f'/api/sources/{source_id}/toggle')
  tabs=cli.req('GET',f'/api/sources/{source_id}/tables')
  if not any(t['tableName']=='orders' and t['enabled'] for t in tabs):
   cli.req('POST',f'/api/sources/{source_id}/tables',{'name':'orders'})
  for col in ('order_id','amount'):
   allowed=cli.req('GET',f'/api/sources/{source_id}/tables/orders/columns')
   if not any(c['columnName']==col and c['enabled'] for c in allowed):
    cli.req('POST',f'/api/sources/{source_id}/tables/orders/columns',{'name':col})
  state[ent['key']+'_source_id']=source_id
  print('SOURCE: '+ent['type']+' enabled with orders(order_id, amount) allowlist')

 agents=cli.req('GET','/api/agents')
 if 'agent_token' not in creds:
  created=cli.req('POST','/api/agents',{'name':'P0 演示外部 Agent','ownerId':owner_id,'validDays':30})
  creds['agent_token']=created['token'];state['agent_id']=created['id']
  write_private(CREDS,creds);state_save()
 agent_id=state['agent_id']
 for ent in data:
  sid=ent['sourceId']
  granted=cli.req('GET',f'/api/agents/{agent_id}/grants')
  if not any(t['sourceId']==sid and t['tableName']=='orders' for t in granted['tables']):
   cli.req('POST',f'/api/agents/{agent_id}/tables',
     {'sourceId':sid,'tableName':'orders'})
  for col in ('order_id','amount'):
   grants=cli.req('GET',f'/api/agents/{agent_id}/grants')
   if not any(c['sourceId']==sid and c['columnName']==col for c in grants['columns']):
    cli.req('POST',f'/api/agents/{agent_id}/columns',
      {'sourceId':sid,'tableName':'orders','columnName':col})
 print('AGENT: token created; table and column grants for both sources')

 rules=cli.req('GET','/api/rules')
 desired=[
   {'name':'P0 MySQL small read','sourceId':data[0]['sourceId'],
     'agentId':agent_id,'tableName':'orders','columnName':None,'minRows':1,'maxRows':5,
     'action':'ALLOW','approverId':None,'priority':10},
   {'name':'P0 MySQL large read approval','sourceId':data[0]['sourceId'],
     'agentId':agent_id,'tableName':'orders','columnName':None,'minRows':6,'maxRows':200,
     'action':'APPROVAL','approverId':reviewer_id,'priority':10},
   {'name':'P0 StarRocks read','sourceId':data[1]['sourceId'],
     'agentId':agent_id,'tableName':'orders','columnName':None,'minRows':1,'maxRows':200,
     'action':'ALLOW','approverId':None,'priority':10}
 ]
 for item in desired:
  if not any(r['name']==item['name'] and r['enabled'] for r in rules):
   cli.req('POST','/api/rules',item)
 print('RULES: 2 direct-allow and 1 human-approval rule installed')
 token=creds['agent_token']
 assert 'result' in mcp(token,'initialize',{'capabilities':{},'clientInfo':{'name':'p0-real-smoke','version':'1'}})
 listing=mcp(token,'tools/list')['result']['tools']
 assert len(listing)==5
 assigned=call(token,'list_datasources',{})
 assert len([d for d in assigned if d['id'] in [i['sourceId'] for i in data]])==2
 for item in data:
  tbl=call(token,'get_schema',{'sourceId':item['sourceId']})
  assert any(t['tableName']=='orders' for t in tbl)
  cols=call(token,'describe_table',{'sourceId':item['sourceId'],'tableName':'orders'})
  assert {x['columnName'] for x in cols}=={'order_id','amount'}
 print('MCP: real HTTPS initialized; 5 tools; both schemas discoverable')
 base={'tableName':'orders','columns':['order_id','amount']}
 result={}
 for item in data:
  query={**base,'sourceId':item['sourceId'],'limit':3}
  output=call(token,'execute_readonly',query)
  assert output.get('status')=='SUCCESS',f"{item['type']} unexpected {output}"
  assert output['rowCount']==3 and len(output['rows'])==3
  assert all('order_id' in row and 'amount' in row for row in output['rows'])
  result[item['key']]=output['rowCount']
  # Negative field access test must be DENIED without opening the database.
  no=call(token,'execute_readonly',{**query,'columns':['customer_note']})
  assert 'error' in no, 'Unapproved customer_note must be denied'
 print('REAL_JDBC: MySQL and StarRocks read succeeded, 3 real rows each; disallowed column denied')
 # Actual internal approver: no MOCK of business execution.
 q={**base,'sourceId':data[0]['sourceId'],'limit':7}
 pending=call(token,'execute_readonly',q)
 assert pending.get('status')=='PENDING',f"expected pending: {pending}"
 request_id=pending['requestId'];state['last_request_id']=request_id;state_save()
 assert call(token,'get_access_request',{'requestId':request_id})['status']=='PENDING'
 # Owner has no ability to approve.
 unauthorized=owner.req('POST',f'/api/requests/{request_id}/approve',
  {'reason':'P0 unauthorized owner attempt'},expect=(403,))
 assert unauthorized is not None
 pending_ui=reviewer.req('GET','/api/requests?scope=pending')
 assert any(q['id']==request_id for q in pending_ui)
 approved=reviewer.req('POST',f'/api/requests/{request_id}/approve',
  {'reason':'P0 approval of isolated demonstration query'})
 assert approved['status']=='APPROVED'
 assert call(token,'get_access_request',{'requestId':request_id})['status']=='APPROVED'
 completed=call(token,'execute_readonly',q)
 assert completed.get('status')=='SUCCESS',f"approved read not successful: {completed}"
 assert completed['requestId']==request_id and completed['rowCount']==3
 assert call(token,'get_access_request',{'requestId':request_id})['status']=='SUCCESS'
 again=call(token,'execute_readonly',q)
 assert again.get('status')=='PENDING' and again['requestId']!=request_id
 records=cli.req('GET','/api/requests?scope=all')
 assert any(r['id']==request_id and r['status']=='SUCCESS' and r['returnedRows']==3 for r in records)
 audit=cli.req('GET','/api/audit')
 assert any(a.get('requestId')==request_id and a['result']=='SUCCESS' for a in audit)
 print('APPROVAL: actual owner denied; independent reviewer approved; JDBC read resumed; single-use; audit confirmed')
 # Do not leave dangling pending request after demo. Approval remains pending for next tester;
 # revoke that untouched second pending approval using assigned reviewer.
 if again.get('requestId'):
  reviewer.req('POST',f"/api/requests/{again['requestId']}/reject",
    {'reason':'Close test-only subsequent request'})
 summary={
  'mysql_rows':result['mysql'],'starrocks_rows':result['starrocks'],
  'mcp_tools':len(listing),'status':'SUCCESS','approved_request_id':request_id,
  'requester':'p0_demo_owner','reviewer':'p0_demo_reviewer',
  'demo_db':'dg_p0_demo','checked_on':time.strftime('%Y-%m-%dT%H:%M:%S%z')
 }
 state['summary']=summary;state_save()
 return summary

def write_report(summary):
 REPORT.parent.mkdir(parents=True,exist_ok=True)
 REPORT.write_text("""# P0 真实业务链路验收（开发机）
> 该文档仅包含非敏感测试结论。演示账号密码和 Agent Token 仅存储在开发机 root-only 配置下，未提交 Git。
 
## 验收前提
- 演示专用数据库：MySQL 8.4 与 StarRocks 3.5 的独立 `dg_p0_demo`，仅包含 3 行合成测试数据。
- 读取账号：两个库独立的 `dg_p0_demo_ro`，只授予指定表 `SELECT`，不开放 DDL/DML。
- 系统内部用户：`p0_demo_owner`（READER）与 `p0_demo_reviewer`（APPROVER），各自有独立口令。
- 公网 MCP：`https://110.42.132.44/mcp`，HTTPS 校验证书，使用 Bearer Agent Token。
- 演示账号与授权作用域只针对 `dg_p0_demo.orders`；不会访问或修改已有业务库。
 
## 实测结果
| 验收项 | 结果 |
| --- | --- |
| 平台连接 MySQL 只读数据源 | 通过 |
| 平台连接 StarRocks 只读数据源 | 通过 |
| MCP initialize / tools/list（5 工具） | 通过 |
| Agent 仅看到明确授权的数据源/表/字段 | 通过 |
| MySQL 真实 JDBC SELECT | 通过，返回 %s 行 |
| StarRocks 真实 JDBC SELECT | 通过，返回 %s 行 |
| 未授权字段被拒绝 | 通过 |
| MySQL 申请产生 PENDING | 通过 |
| 非审批人员审批被拒绝 | 通过 |
| 独立 APPROVER 登录后批准 | 通过 |
| Agent 轮询 APPROVED、原请求重试成功 | 通过 |
| 单次审批不可重复使用 | 通过；再次调用产生新申请 |
| 请求记录和操作审计 | 通过 |
| 临时演示初始化管理员已停用 | 通过 |
 
## 范围与待完成
- 已进行真实 HTTPS、Spring Boot、MySQL/StarRocks JDBC 的完整业务查询和审批验证，非 Mock。
- 未将 StarRocks 演示实例的明文 loopback 连接配置当作正式环境 TLS 验收。
- 尚需独立外部 MCP SDK/Agent 应用兼容性认证，当前使用真实 JSON-RPC HTTP 客户端。
- 仍需权限撤销竞态、故障恢复、压力测试、限流、审计防篡改和正式生产角色验收。
- 禁止将凭据或 Agent Token 写入本仓库；开发机凭据保存在 `/etc/dataguard-new/p0-demo/credentials.json`，仅 root 可读。
 
最近成功审批请求：`%s`。
"""%(summary['mysql_rows'],summary['starrocks_rows'],summary['approved_request_id']))
 print('DOCUMENT: '+str(REPORT)+' written (no credentials)')

def main():
 if os.geteuid()!=0:raise SystemExit('Run as root on development host')
 print('START: P0 isolated live-read approval smoke')
 bootstrap_db()
 seed_temporary_admin()
 try:
  summary=provision_and_validate()
 finally:
  disable_temporary_admin()
 if summary['status']=='SUCCESS':
  write_report(summary)
  print('SUCCESS: P0 MySQL + StarRocks + MCP approval flow certified in dev environment')

if __name__=='__main__':
 main()
