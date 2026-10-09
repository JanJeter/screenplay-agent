// Read-only visual verification against the local app. Never generates content.
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const { chromium, expect } = require(path.join(root, 'apps/storyboard-web/node_modules/@playwright/test'));
const out = path.join(root, '.local/workbench-dev/ui-redesign');
const web = 'http://127.0.0.1:5174';
const api = 'http://127.0.0.1:18084/api/v1';

(async () => {
  await fs.mkdir(out, { recursive: true });
  const state = JSON.parse(await fs.readFile(path.join(root, '.local/workbench-dev/state.json'), 'utf8'));
  const ledgerFile = state.activityId ? path.join(root, '.local/workbench-dev/budget', state.activityId + '.json') : null;
  const ledgerBefore = ledgerFile ? await fs.readFile(ledgerFile, 'utf8') : null;
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage(); const errors = []; const results = [];
  page.on('pageerror', error => errors.push(error.message));
  await context.route('**/api/v1/**', route => {
    const req = route.request(); const url = new URL(req.url());
    if (!['GET', 'HEAD'].includes(req.method()) && !/^\/api\/v1\/auth\/(login|refresh)$/.test(url.pathname)) {
      errors.push('Blocked unexpected mutation: ' + req.method() + ' ' + url.pathname);
      return route.abort();
    }
    return route.continue();
  });
  async function read(route) {
    return page.evaluate(async ({ url }) => {
      const session = JSON.parse(sessionStorage.getItem('storyboard-web.session') || 'null');
      const response = await fetch(url, { headers: { Authorization: 'Bearer ' + session.accessToken } });
      if (!response.ok) throw new Error('Read-only capture request failed: ' + response.status);
      return response.json();
    }, { url: api + route });
  }
  async function capture(name) {
    await page.screenshot({ path: path.join(out, name + '.png'), fullPage: true });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
    results.push({ screen: name, horizontalOverflow: overflow });
    assert.equal(overflow, false, name + ' must not overflow');
  }
  try {
    await page.goto(web + '/login'); await capture('login-desktop');
    await page.setViewportSize({ width: 390, height: 844 }); await capture('login-mobile');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByLabel('邮箱', { exact: true }).fill(process.env.E2E_LOGIN_EMAIL || 'admin@demo.com');
    await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_LOGIN_PASSWORD || 'admin12345');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page).toHaveURL(/\/projects$/); await expect(page.locator('.project-card').first()).toBeVisible();
    await capture('projects-desktop');
    await page.setViewportSize({ width: 390, height: 844 }); await capture('projects-mobile');
    await page.setViewportSize({ width: 1440, height: 1000 });
    const projects = await read('/screenplay/projects');
    let chosen = null;
    for (const project of projects) {
      const scripts = await read('/screenplay/projects/' + project.id + '/scripts');
      for (const script of scripts) {
        const boards = (await read('/screenplay/projects/' + project.id + '/storyboards?scriptId=' + encodeURIComponent(script.id) + '&page=0&size=20')).items;
        if (boards.length) { chosen = { project, script, board: boards[0] }; break; }
      }
      if (chosen) break;
    }
    assert.ok(chosen, 'A saved storyboard is required for read-only capture');
    await page.goto(web + '/projects/' + chosen.project.id + '/scripts');
    await expect(page.locator('.script-version').first()).toBeVisible(); await capture('scripts-desktop');
    await page.getByRole('button', { name: '粘贴剧本', exact: true }).click();
    await expect(page.getByLabel('剧本文本')).toBeVisible(); await capture('script-import-desktop');
    await page.setViewportSize({ width: 390, height: 844 }); await capture('script-import-mobile');
    await page.setViewportSize({ width: 1440, height: 1000 });
    const boardUrl = '/projects/' + chosen.project.id + '/scripts/' + chosen.script.id + '/storyboard?storyboard=' + chosen.board.id;
    await page.goto(web + boardUrl); await expect(page.locator('.shot-card').first()).toBeVisible();
    await capture('workspace-desktop');
    await page.setViewportSize({ width: 390, height: 844 }); await capture('workspace-mobile');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByRole('button', { name: '生成另一份', exact: true }).click();
    await expect(page.getByLabel('创作要求')).toBeVisible(); await capture('generation-desktop');
    for (const section of ['users', 'roles', 'runs', 'usage']) {
      await page.goto(web + '/admin/' + section);
      await expect(page.getByRole('table')).toBeVisible(); await capture('admin-' + section + '-desktop');
      await page.setViewportSize({ width: 390, height: 844 }); await capture('admin-' + section + '-mobile');
      await page.setViewportSize({ width: 1440, height: 1000 });
    }
    assert.deepEqual(errors, []);
    if (ledgerFile) assert.equal(await fs.readFile(ledgerFile, 'utf8'), ledgerBefore);
    await fs.writeFile(path.join(out, 'visual-verification.json'), JSON.stringify({ verifiedAt: new Date().toISOString(), results, pageErrors: errors, modelBudgetUnchanged: true }, null, 2));
    console.log(JSON.stringify({ screenshots: results.length, pageErrors: errors, modelBudgetUnchanged: true }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
