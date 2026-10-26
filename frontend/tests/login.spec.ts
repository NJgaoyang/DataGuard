import {test,expect} from '@playwright/test';

test('login form actually submits, reports invalid credentials, and opens the workspace',async({page})=>{
 let authenticated=false;
 const submissions:{username:string;password:string;csrf:string|null}[]=[];
 await page.route('**/api/csrf',route=>route.fulfill({
  status:200,contentType:'application/json',body:JSON.stringify({token:'browser-test-csrf',header:'X-CSRF-TOKEN'})
 }));
 await page.route('**/api/session',route=>route.fulfill({
  status:authenticated?200:401,contentType:'application/json',
  body:authenticated?JSON.stringify({username:'admin',displayName:'管理员',roleCode:'ADMIN',mustChangePassword:false}):'{}'
 }));
 await page.route('**/login',async route=>{
  const r=route.request(),data=new URLSearchParams(r.postData()||'');
  submissions.push({username:data.get('username')||'',password:data.get('password')||'',csrf:r.headers()['x-csrf-token']||null});
  authenticated=data.get('username')==='admin'&&data.get('password')==='correct-test-only';
  await route.fulfill({status:authenticated?200:401,contentType:'application/json',
   body:authenticated?'{"ok":true}':'{"error":"Invalid credentials"}'});
 });
 await page.route('**/api/dashboard',route=>route.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({stats:{requests:0,pending:0,denied:0,success:0},recent:[]})}));
 await page.goto('/');
 await expect(page.locator('.login-shell')).toBeVisible();
 await page.locator('.login-panel button[type="submit"]').click();
 await expect(page.getByText('请输入用户名')).toBeVisible();
 expect(submissions).toHaveLength(0);
 await page.locator('input[autocomplete="username"]').fill('admin');
 await page.locator('input[autocomplete="current-password"]').fill('incorrect');
 await page.locator('.login-panel button[type="submit"]').click();
 await expect(page.locator('.login-error')).toContainText('用户名或密码错误');
 expect(submissions).toEqual([{username:'admin',password:'incorrect',csrf:'browser-test-csrf'}]);
 await page.locator('input[autocomplete="current-password"]').fill('correct-test-only');
 await page.locator('input[autocomplete="current-password"]').press('Enter');
 await expect(page.locator('nav[aria-label="主导航"]')).toBeVisible();
 expect(submissions).toHaveLength(2);
 expect(submissions[1]).toMatchObject({username:'admin',password:'correct-test-only',csrf:'browser-test-csrf'});
 await expect(page.locator('.workspace-head h1')).toHaveText('工作台');
});

test('first-login password change sends CSRF token rotated after login',async({page})=>{
 let authenticated=false;
 let csrfRefreshes=0;
 const loginToken='csrf-before-login';
 const sessionToken='csrf-after-login';
 const passwordRequests:{csrf:string|undefined;currentPassword:string;newPassword:string}[]=[];
 await page.route('**/api/csrf',async route=>{
  csrfRefreshes++;
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({
   token:authenticated?sessionToken:loginToken,header:'X-CSRF-TOKEN'
  })});
 });
 await page.route('**/api/session',async route=>{
  await route.fulfill({status:authenticated?200:401,contentType:'application/json',
   body:authenticated?JSON.stringify({
    username:'admin',displayName:'管理员',roleCode:'ADMIN',mustChangePassword:true
   }):'{}'});
 });
 await page.route('**/login',async route=>{
  expect(route.request().headers()['x-csrf-token']).toBe(loginToken);
  authenticated=true;
  await route.fulfill({status:200,contentType:'application/json',body:'{"ok":true}'});
 });
 await page.route('**/api/password',async route=>{
  const body=route.request().postDataJSON() as {currentPassword:string;newPassword:string};
  const csrf=route.request().headers()['x-csrf-token'];
  passwordRequests.push({csrf,currentPassword:body.currentPassword,newPassword:body.newPassword});
  const accepted=csrf===sessionToken&&body.currentPassword==='current-test-password';
  if(accepted)authenticated=false;
  await route.fulfill({status:accepted?200:403,contentType:'application/json',
   body:JSON.stringify(accepted?{reauthenticate:true}:{message:'CSRF token invalid'})});
 });
 await page.route('**/api/dashboard',route=>route.fulfill({status:200,contentType:'application/json',
  body:JSON.stringify({stats:{requests:0,pending:0,denied:0,success:0},recent:[]})}));
 await page.goto('/');
 await page.locator('input[autocomplete="username"]').fill('admin');
 await page.locator('input[autocomplete="current-password"]').fill('current-test-password');
 await page.locator('.login-panel button[type="submit"]').click();
 await expect(page.locator('.ant-modal')).toContainText('首次登录安全设置');
 await page.locator('.ant-modal input[autocomplete="current-password"]').fill('current-test-password');
 await page.locator('.ant-modal input[autocomplete="new-password"]').fill('NewPassword-123!test');
 await page.locator('.ant-modal-footer .ant-btn-primary').click();
 await expect(page.locator('.login-shell')).toBeVisible();
 expect(passwordRequests).toEqual([{
  csrf:sessionToken,currentPassword:'current-test-password',newPassword:'NewPassword-123!test'
 }]);
 expect(csrfRefreshes).toBeGreaterThanOrEqual(3);
});
