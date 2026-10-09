// Real local HTTP/browser verification. Creates one disposable team member;
// sends only local .eml files and never starts a model task.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const { chromium, expect } = require(path.join(root, 'apps/storyboard-web/node_modules/@playwright/test'));
const web = 'http://127.0.0.1:5174';
const api = 'http://127.0.0.1:18084/api/v1';
const local = path.join(root, '.local/workbench-dev');
const artifacts = path.join(local, 'accounts-browser');

async function request(route, { method = 'GET', body, accessToken } = {}) {
  if (!/^\/(auth|admin)\//.test(route)) throw new Error('Verification allows only account/admin routes');
  const response = await fetch(api + route, { method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
    body: body ? JSON.stringify(body) : undefined });
  const data = await response.json().catch(() => ({}));
  return { status: response.status, data };
}
async function mailLink(email, kind) {
  const directory = path.join(local, 'mail');
  for (let attempt = 0; attempt < 30; attempt++) {
    const files = await fs.readdir(directory).catch(() => []);
    const matches = [];
    for (const name of files.filter(name => name.endsWith('.eml'))) {
      const file = path.join(directory, name);
      const text = await fs.readFile(file, 'utf8');
      if (!text.startsWith(`To: ${email}\n`)) continue;
      const link = text.match(new RegExp(`http://127\\.0\\.0\\.1:5174/${kind}\\?token=[A-Za-z0-9_-]+`))?.[0];
      if (link) matches.push({ link, time: (await fs.stat(file)).mtimeMs });
    }
    if (matches.length) return matches.sort((a, b) => b.time - a.time)[0].link;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error(`Local ${kind} email did not arrive`);
}
async function tokens(page) {
  return page.evaluate(() => JSON.parse(sessionStorage.getItem('storyboard-web.session') || 'null'));
}
async function login(page, email, password) {
  await page.goto(web + '/login');
  await page.getByLabel('邮箱', { exact: true }).fill(email);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/projects$/);
}

(async () => {
  await fs.mkdir(artifacts, { recursive: true });
  const state = JSON.parse(await fs.readFile(path.join(local, 'state.json'), 'utf8'));
  const ledgerPath = state.activityId ? path.join(local, 'budget', state.activityId + '.json') : null;
  const initialLedger = ledgerPath ? await fs.readFile(ledgerPath, 'utf8') : null;
  const stamp = Date.now().toString(36);
  const email = `accounts-${stamp}@example.test`;
  const password = crypto.randomBytes(18).toString('base64url');
  const newPassword = crypto.randomBytes(18).toString('base64url');
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  context.on('page', child => child.on('pageerror', error => pageErrors.push(error.message)));
  page.on('pageerror', error => pageErrors.push(error.message));
  let adminToken, userId, memberRoleId;
  const report = { email, verifiedAt: null, checks: [] };
  const pass = name => { report.checks.push(name); console.log('PASS: ' + name); };
  try {
    // Any accidental generation request is aborted before reaching Java.
    await context.route('**/api/v1/screenplay/agent/**', route => route.abort('blockedbyclient'));
    await context.route('**/generations', route => route.abort('blockedbyclient'));
    await context.route('**/regenerations', route => route.abort('blockedbyclient'));
    await page.goto(web + '/register');
    await page.getByLabel('姓名', { exact: true }).fill('账号流程验证 ' + stamp);
    await page.getByLabel('邮箱', { exact: true }).fill(email);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '注册并发送验证邮件' }).click();
    await expect(page.getByRole('status')).toContainText('验证邮件');
    const unverified = await request('/auth/login', { method: 'POST', body: { email, password } });
    assert.equal(unverified.status, 403, 'unverified user must not log in');
    pass('Browser registration writes local verification email; login requires verification');

    const verifyUrl = await mailLink(email, 'verify-email');
    await page.goto(verifyUrl);
    await page.getByRole('button', { name: '验证邮箱', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('验证');
    assert.equal((await request('/auth/verify-email', { method: 'POST', body: { token: new URL(verifyUrl).searchParams.get('token') } })).status, 400);
    await login(page, email.toUpperCase(), password);
    const originalSession = await tokens(page);
    const profile = await request('/auth/me', { accessToken: originalSession.accessToken });
    assert.equal(profile.status, 200); assert.equal(profile.data.emailVerified, true);
    assert.deepEqual(profile.data.roles, ['USER']); userId = profile.data.id;
    for (const route of ['/admin/users', '/admin/roles', '/admin/runs', '/admin/usage']) {
      assert.equal((await request(route, { accessToken: originalSession.accessToken })).status, 403);
    }
    pass('Email token is one-use; member login works and admin APIs reject members');

    await page.goto(web + '/forgot-password');
    await page.getByLabel('邮箱', { exact: true }).fill(email);
    await page.getByRole('button', { name: '发送重置邮件' }).click();
    await expect(page.getByRole('status')).toContainText('重置邮件');
    const resetUrl = await mailLink(email, 'reset-password');
    await page.goto(resetUrl);
    await page.getByLabel('新密码', { exact: true }).fill(newPassword);
    await page.getByLabel('确认新密码', { exact: true }).fill(newPassword);
    await page.getByRole('button', { name: '重置密码', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('密码');
    assert.equal((await request('/auth/me', { accessToken: originalSession.accessToken })).status, 401);
    assert.equal((await request('/auth/refresh', { method: 'POST', body: { refreshToken: originalSession.refreshToken } })).status, 401);
    assert.equal((await request('/auth/reset-password', { method: 'POST', body: { token: new URL(resetUrl).searchParams.get('token'), newPassword } })).status, 400);
    const badLogin = await request('/auth/login', { method: 'POST', body: { email, password } });
    assert.equal(badLogin.status, 401);
    await page.evaluate(() => sessionStorage.removeItem('storyboard-web.session'));
    await login(page, email, newPassword);
    const memberSession = await tokens(page);
    pass('Browser password reset invalidates old password, access token, refresh token and reset link');

    const adminPage = await context.newPage();
    await login(adminPage, 'admin@demo.com', 'admin12345');
    adminToken = (await tokens(adminPage)).accessToken;
    const listing = await request(`/admin/users?q=${encodeURIComponent(email)}`, { accessToken: adminToken });
    assert.equal(listing.status, 200); assert.equal(listing.data.items.length, 1);
    assert.equal(listing.data.items[0].id, userId);
    const roles = await request('/admin/roles', { accessToken: adminToken });
    memberRoleId = roles.data.find(role => role.name === 'USER').id;
    const adminRoleId = roles.data.find(role => role.name === 'ADMIN').id;
    const adminMe = (await request('/auth/me', { accessToken: adminToken })).data;
    assert.equal((await request(`/admin/users/${adminMe.id}/status`, { method: 'PATCH', body: { enabled: false }, accessToken: adminToken })).status, 409);
    assert.equal((await request(`/admin/users/${adminMe.id}/roles`, { method: 'PUT', body: { roleIds: [memberRoleId] }, accessToken: adminToken })).status, 409);
    assert.equal((await request(`/admin/roles/${adminRoleId}`, { method: 'PUT', body: { name: 'ADMIN', permissions: [] }, accessToken: adminToken })).status, 409);
    const roleName = `QA_${stamp.toUpperCase()}`;
    await adminPage.goto(web + '/admin/roles');
    await adminPage.getByRole('button', { name: '创建角色', exact: true }).click();
    await adminPage.getByLabel('角色名称', { exact: true }).fill(roleName);
    await adminPage.getByRole('checkbox', { name: /修改个人资料/ }).check();
    await adminPage.getByRole('button', { name: '保存角色权限' }).click();
    await expect(adminPage.getByRole('status')).toContainText('角色权限已保存');
    await adminPage.goto(web + '/admin/users');
    await adminPage.getByLabel('搜索用户', { exact: true }).fill(email);
    await adminPage.getByRole('button', { name: '搜索', exact: true }).click();
    const userRow = adminPage.getByRole('row').filter({ hasText: email });
    await expect(userRow).toHaveCount(1);
    await userRow.getByRole('button', { name: '分配角色' }).click();
    await adminPage.getByRole('checkbox', { name: new RegExp(roleName) }).check();
    await adminPage.getByRole('button', { name: '保存角色', exact: true }).click();
    await expect(adminPage.getByRole('status')).toContainText('用户角色已更新');
    await expect(userRow).toContainText(roleName);
    adminPage.once('dialog', dialog => dialog.accept());
    await userRow.getByRole('button', { name: '停用', exact: true }).click();
    await expect(adminPage.getByRole('status')).toContainText('用户已停用');
    assert.equal((await request('/auth/me', { accessToken: memberSession.accessToken })).status, 401);
    assert.equal((await request('/auth/login', { method: 'POST', body: { email, password: newPassword } })).status, 403);
    await userRow.getByRole('button', { name: '启用', exact: true }).click();
    await expect(adminPage.getByRole('status')).toContainText('用户已启用');
    assert.equal((await request('/auth/login', { method: 'POST', body: { email, password: newPassword } })).status, 200);
    pass('Admin searches users, assigns roles, disables/re-enables accounts; self-lockout and system role changes are rejected');

    for (const route of ['/admin/users', '/admin/roles', '/admin/runs', '/admin/usage']) {
      const loaded = adminPage.waitForResponse(response => response.url().startsWith(api + route) && response.request().method() === 'GET' && response.status() === 200);
      await adminPage.goto(web + route);
      await loaded;
      await expect(adminPage.getByRole('table')).toBeVisible();
      await expect(adminPage.getByRole('alert')).toHaveCount(0);
      await adminPage.screenshot({ path: path.join(artifacts, route.split('/').pop() + '.png'), fullPage: true });
    }
    const usage = await request('/admin/usage', { accessToken: adminToken });
    const runs = await request('/admin/runs', { accessToken: adminToken });
    assert.equal(usage.status, 200); assert.equal(runs.status, 200);
    assert.ok(usage.data.totalRuns > 0); assert.ok(runs.data.items.length > 0);
    report.usage = usage.data;
    await adminPage.setViewportSize({ width: 390, height: 844 });
    await adminPage.goto(web + '/admin/users');
    await expect(adminPage.getByRole('table')).toBeVisible();
    assert.ok(await adminPage.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'mobile page must not overflow horizontally');
    await adminPage.screenshot({ path: path.join(artifacts, 'users-mobile.png'), fullPage: true });
    pass('Actual admin pages load saved task/usage data on desktop and mobile');
    assert.deepEqual(pageErrors, []);
    if (ledgerPath) assert.equal(await fs.readFile(ledgerPath, 'utf8'), initialLedger, 'account verification must not spend model budget');
    pass('No browser page errors and no model calls or budget changes');
    report.verifiedAt = new Date().toISOString();
    await fs.writeFile(path.join(artifacts, 'account-flow.json'), JSON.stringify(report, null, 2), 'utf8');
  } finally {
    // Leave the test account disabled and without a management role.
    if (adminToken && userId) {
      if (memberRoleId) await request(`/admin/users/${userId}/roles`, { method: 'PUT', body: { roleIds: [memberRoleId] }, accessToken: adminToken });
      await request(`/admin/users/${userId}/status`, { method: 'PATCH', body: { enabled: false }, accessToken: adminToken });
    }
    await browser.close();
  }
})().catch(error => {
  console.error(String(error.message).replace(/([?&]token=)[^\s&"']+/g, '$1[redacted]'));
  process.exitCode = 1;
});
