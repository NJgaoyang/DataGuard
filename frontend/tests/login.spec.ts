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
