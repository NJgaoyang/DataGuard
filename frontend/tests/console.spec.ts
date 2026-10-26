import {test,expect} from '@playwright/test';

const sources=[
 {id:'s1',name:'订单数据库',type:'MYSQL',host:'192.168.1.10',port:3306,databaseName:'order_db',username:'dg_read',enabled:true,authorizedTables:5},
 {id:'s2',name:'用户数据库',type:'MYSQL',host:'192.168.1.11',port:3306,databaseName:'users',username:'dg_read',enabled:true,authorizedTables:2},
 {id:'s3',name:'分析数据库',type:'STARROCKS',host:'192.168.1.20',port:9030,databaseName:'analytics',username:'dg_read',enabled:true,authorizedTables:12}
];
const agents=[
 {id:'a1',name:'数据分析 Agent',ownerId:'u1',ownerName:'李四',enabled:true,expiresAt:'2028-12-30T00:00:00',lastAccess:'2026-10-25T10:32:21'},
 {id:'a2',name:'客服 Agent',ownerId:'u2',ownerName:'张三',enabled:true,expiresAt:'2028-12-30T00:00:00',lastAccess:'2026-10-25T10:31:08'},
 {id:'a3',name:'报表 Agent',ownerId:'u3',ownerName:'王五',enabled:true,expiresAt:'2028-12-30T00:00:00',lastAccess:'2026-10-25T10:29:17'}
];
const rules=[
 {id:'r1',name:'订单表小批量读取',sourceId:'s1',sourceName:'订单数据库',tableName:'orders',minRows:1,maxRows:50,action:'ALLOW',priority:1,enabled:true},
 {id:'r2',name:'订单表批量读取',sourceId:'s1',sourceName:'订单数据库',tableName:'orders',minRows:51,maxRows:200,action:'APPROVAL',approverId:'uadmin',approverName:'管理员',priority:2,enabled:true},
 {id:'r3',name:'敏感字段访问',tableName:'users',columnName:'id_card',minRows:1,maxRows:200,action:'DENY',priority:1,enabled:true}
];
const requests=[
 {id:'q1',createdAt:'2026-10-25T10:31:08',expiresAt:'2028-12-30T00:00:00',tableName:'users',columnsRequested:'user_id,username,email',
   requestedRows:150,returnedRows:null,status:'PENDING',action:'APPROVAL',reason:null,ruleName:'敏感数据人工审核',ruleId:'r2',
   agentName:'客服 Agent',ownerUsername:'reader',requesterName:'张三',approverUsername:'admin',approverName:'管理员',sourceName:'用户数据库',durationMs:null},
 {id:'q2',createdAt:'2026-10-25T10:32:21',expiresAt:'2028-12-30T00:00:00',tableName:'orders',columnsRequested:'order_id,amount,created_at',
   requestedRows:50,returnedRows:50,status:'SUCCESS',action:'ALLOW',reason:null,ruleName:'订单表小批量读取',ruleId:'r1',
   agentName:'数据分析 Agent',ownerUsername:'reader',requesterName:'李四',approverUsername:null,approverName:null,sourceName:'订单数据库',durationMs:320},
 {id:'q3',createdAt:'2026-10-25T10:29:17',expiresAt:'2028-12-30T00:00:00',tableName:'finance',columnsRequested:'revenue',
   requestedRows:500,returnedRows:0,status:'DENIED',action:'DENY',reason:'Exceeded safety policy',ruleName:'高风险规则',ruleId:'r3',
   agentName:'报表 Agent',ownerUsername:'reader',requesterName:'王五',approverUsername:null,approverName:null,sourceName:'分析数据库',durationMs:120}
];

test.beforeEach(async ({page})=>{
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url());
  const path=url.pathname;
  let data:unknown=[];
  if(path==='/api/csrf')data={token:'mock-csrf',header:'X-CSRF-TOKEN'};
  else if(path==='/api/session')data={username:'admin',displayName:'管理员',roleCode:'ADMIN',mustChangePassword:false};
  else if(path==='/api/dashboard')data={stats:{requests:1286,pending:5,denied:12,success:1183},
    recent:requests.map(x=>({id:x.id,agentName:x.agentName,tableName:x.tableName,status:x.status,requestedRows:x.requestedRows,createdAt:x.createdAt}))};
  else if(path==='/api/sources')data=sources;
  else if(path==='/api/sources/s1/tables')data=[{tableName:'orders',enabled:true,authorizedColumns:4}];
  else if(path==='/api/sources/s1/tables/orders/physical-columns')data=[
   {columnName:'order_id',dataType:'bigint',enabled:true,masked:false},
   {columnName:'user_id',dataType:'bigint',enabled:true,masked:false},
   {columnName:'amount',dataType:'decimal',enabled:true,masked:false},
   {columnName:'created_at',dataType:'datetime',enabled:true,masked:false},
   {columnName:'phone',dataType:'varchar',enabled:false,masked:false},
   {columnName:'id_card',dataType:'varchar',enabled:false,masked:false}
  ];
  else if(path==='/api/agents')data=agents;
  else if(path==='/api/agents/a1/grants')data={
    tables:[{sourceId:'s1',tableName:'orders'}],
    columns:[{sourceId:'s1',tableName:'orders',columnName:'order_id'},
      {sourceId:'s1',tableName:'orders',columnName:'amount'},
      {sourceId:'s1',tableName:'orders',columnName:'created_at'}]
  };
  else if(path==='/api/rules')data=rules;
  else if(path==='/api/approvers')data=[{id:'uadmin',displayName:'管理员',username:'admin'}];
  else if(path==='/api/users')data=[{id:'uadmin',username:'admin',displayName:'管理员',roleCode:'ADMIN',enabled:true}];
  else if(path==='/api/requests'){
    const scope=url.searchParams.get('scope');
    data=scope==='pending'?requests.filter(r=>r.status==='PENDING'):scope==='handled'?requests.filter(r=>r.status!=='PENDING'):requests;
  }
  else if(path==='/api/audit')data=[];
  else if(route.request().method()!=='GET')data={updated:true,status:'APPROVED'};
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(data)});
 });
 await page.goto('/');
 await expect(page.locator('nav[aria-label="主导航"] .nav-item')).toHaveCount(7);
});
const navigate=async(page:import('@playwright/test').Page,text:string)=>{
 await page.locator('nav[aria-label="主导航"] .nav-item').filter({hasText:text}).click();
 await expect(page.locator('.workspace-head h1')).toHaveText(text);
};
test('six actual menu pages retain screenshot layout and detail drawers',async ({page})=>{
 await expect(page.locator('nav[aria-label="主导航"] .nav-item')).toHaveText([
  '工作台','数据源','Agent 管理','访问规则','审批中心','访问记录','系统设置'
 ]);
 await expect(page.locator('.metric')).toHaveCount(4);
 await page.screenshot({animations:'disabled',path:test.info().outputPath('01-dashboard.png'),fullPage:true});

 await navigate(page,'数据源');
 await expect(page.getByRole('button',{name:'订单数据库'})).toBeVisible();
 await page.getByRole('button',{name:'订单数据库'}).click();
 await page.locator('.ant-drawer .grant-row').filter({hasText:'orders'}).getByRole('button',{name:'字段权限'}).click();
 await expect(page.locator('.ant-drawer')).toContainText('bigint');
 await expect(page.locator('.ant-drawer')).toContainText('脱敏');
 await page.screenshot({animations:'disabled',path:test.info().outputPath('02-sources-drawer.png'),fullPage:true});
 await page.locator('.ant-drawer .ant-drawer-close').click();

 await navigate(page,'Agent 管理');
 await page.getByRole('button',{name:/数据分析 Agent/}).click();
 await expect(page.locator('.ant-drawer')).toContainText('orders');
 await page.screenshot({animations:'disabled',path:test.info().outputPath('03-agent-drawer.png'),fullPage:true});
 await page.locator('.ant-drawer .ant-drawer-close').click();

 await navigate(page,'访问规则');
 await page.getByRole('button',{name:'订单表批量读取'}).click();
 await expect(page.locator('.ant-drawer')).toContainText('需要审批');
 await page.screenshot({animations:'disabled',path:test.info().outputPath('04-rules-drawer.png'),fullPage:true});
 await page.locator('.ant-drawer .ant-drawer-close').click();

 await navigate(page,'审批中心');
 await expect(page.locator('.ant-table-tbody')).toContainText('客服 Agent');
 await page.locator('.ant-table-tbody').getByRole('button',{name:'审批'}).first().click();
 await expect(page.locator('.ant-drawer')).toContainText('读取字段');
 await page.screenshot({animations:'disabled',path:test.info().outputPath('05-approvals-drawer.png'),fullPage:true});
 await page.locator('.ant-drawer .ant-drawer-close').click();

 await navigate(page,'访问记录');
 await page.locator('.ant-table-tbody').getByRole('button',{name:'查看'}).first().click();
 await expect(page.locator('.ant-drawer')).toContainText('返回行数');
 await page.screenshot({animations:'disabled',path:test.info().outputPath('06-records-drawer.png'),fullPage:true});
 await page.locator('.ant-drawer .ant-drawer-close').click();

 await navigate(page,'系统设置');
 await expect(page.getByRole('tab',{name:'账户信息'})).toBeVisible();
});
test('mobile navigation is usable',async({page})=>{
 await page.setViewportSize({width:390,height:780});
 await expect(page.locator('.mobile-toggle')).toBeVisible();
 await page.locator('.mobile-toggle').click();
 await page.locator('nav[aria-label="主导航"] .nav-item').filter({hasText:'审批中心'}).click();
 await expect(page.locator('.workspace-head h1')).toHaveText('审批中心');
 await page.screenshot({animations:'disabled',path:test.info().outputPath('07-mobile-approvals.png'),fullPage:true});
});
test('authenticated console uses a compact shared layout on every page',async({page})=>{
 await expect(page.locator('.topbar')).toBeVisible();
 const bar=await page.locator('.topbar').boundingBox();
 const tabBar=await page.locator('.console-tabs').boundingBox();
 expect(tabBar!.height).toBeLessThanOrEqual(36);
 const heading=await page.locator('.workspace-head').boundingBox();
 const firstCard=await page.locator('.metric').first().boundingBox();
 expect(bar!.height).toBeLessThanOrEqual(54);
 expect(heading!.height).toBeLessThanOrEqual(34);
 expect(firstCard!.height).toBeLessThanOrEqual(88);
 for(const label of ['数据源','Agent 管理','访问规则','审批中心','访问记录','系统设置']){
  await navigate(page,label);
  await expect(page.locator('.workspace-head')).toBeVisible();
  const ws=await page.locator('.workspace').evaluate(el=>parseFloat(getComputedStyle(el).paddingTop));
  expect(ws).toBeLessThanOrEqual(105); // 52px top bar + 35px tabs + 14px content gap
 }
});

test('top breadcrumb and page tabs work without redesigning the existing console',async({page})=>{
 await expect(page.locator('.console-breadcrumb')).toContainText('工作台');
 await expect(page.locator('.console-tabs')).toBeVisible();
 await expect(page.locator('.metric')).toHaveCount(4);
 const bg=await page.locator('.app-root').evaluate(el=>getComputedStyle(el).backgroundColor);
 expect(bg).toBe('rgb(243, 246, 250)');
 await navigate(page,'数据源');
 await expect(page.locator('.console-breadcrumb')).toContainText('数据源');
 await expect(page.locator('.console-tabs')).toContainText('数据源');
 await navigate(page,'Agent 管理');
 await expect(page.locator('.console-tabs')).toContainText('Agent 管理');
 await page.getByRole('button',{name:'关闭Agent 管理标签'}).click();
 await expect(page.locator('.workspace-head h1')).toHaveText('数据源');
 await page.locator('.console-tab-home').click();
 await expect(page.locator('.workspace-head h1')).toHaveText('工作台');
});
