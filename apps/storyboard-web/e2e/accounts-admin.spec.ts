import { expect, test, type Page } from '@playwright/test'

const profile = { id: 1, email: 'admin@example.com', fullName: '团队管理员', roles: ['ADMIN'], permissions: ['USER_VIEW_ALL'], organizationId: 1, organizationName: '镜场团队', enabled: true, emailVerified: true }
const permission = { code: 'USER_VIEW_ALL', label: '查看所有用户', description: '查看工作区成员资料' }
const baseRoles = [{ id: 1, name: 'ADMIN', system: true, permissions: ['USER_VIEW_ALL'] }, { id: 2, name: 'USER', system: true, permissions: [] }]
const unknownUsage = { mode: 'unknown', providerRequests: 0, inputTokens: null, outputTokens: null, totalTokens: null, estimatedCostUsd: null, costStatus: 'unavailable' }
async function authenticated(page: Page) { await page.addInitScript(() => sessionStorage.setItem('storyboard-web.session', JSON.stringify({ accessToken: 'test-access', refreshToken: 'test-refresh' }))) }

test('account registration, verification and password recovery use their real browser forms', async ({ page }, testInfo) => {
  const submissions: Array<{ path: string; body: Record<string, string> }> = []
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    expect(path).toMatch(/^\/api\/v1\/auth\//)
    submissions.push({ path, body: route.request().postDataJSON() })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ message: path.endsWith('/verify-email') ? '邮箱已验证，请登录。' : path.endsWith('/reset-password') ? '密码已重置，请使用新密码登录。' : '如果该邮箱符合条件，相应邮件已发送。' }) })
  })
  await page.goto('/register')
  await page.getByLabel('姓名').fill('新编剧')
  await page.getByLabel('邮箱').fill('Writer@Example.com')
  await page.getByLabel('密码', { exact: true }).fill('密'.repeat(25))
  await page.getByRole('button', { name: '注册并发送验证邮件' }).click()
  await expect(page.getByRole('alert')).toContainText('72 字节')
  expect(submissions).toHaveLength(0)
  await page.getByLabel('密码', { exact: true }).fill('new-password-123')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('register-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: '注册并发送验证邮件' }).click()
  await expect(page.getByRole('status')).toContainText('相应邮件已发送')
  expect(submissions[0]).toEqual({ path: '/api/v1/auth/register', body: { fullName: '新编剧', email: 'writer@example.com', password: 'new-password-123' } })
  await page.getByRole('link', { name: '重新发送验证邮件' }).click()
  await expect(page.getByLabel('邮箱')).toHaveValue('writer@example.com')
  await page.getByRole('button', { name: '发送验证邮件' }).click()
  await expect(page.getByRole('status')).toBeVisible()
  await page.goto('/verify-email?token=verification-test-token')
  await page.getByRole('button', { name: '验证邮箱', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('邮箱已验证，请登录。')
  await expect(page).toHaveURL(/\/verify-email$/)
  expect(submissions.filter((item) => item.path.endsWith('/verify-email'))).toHaveLength(1)
  await page.goto('/forgot-password')
  await page.getByLabel('邮箱').fill('writer@example.com')
  await page.getByRole('button', { name: '发送重置邮件' }).click()
  await expect(page.getByRole('status')).toBeVisible()
  await page.goto('/reset-password?token=reset-test-token')
  await page.getByLabel('新密码', { exact: true }).fill('changed-password-456')
  await page.getByLabel('确认新密码').fill('different-password')
  await page.getByRole('button', { name: '重置密码', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('两次输入的密码不一致。')
  await page.getByLabel('确认新密码').fill('changed-password-456')
  await page.getByRole('button', { name: '重置密码', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('密码已重置')
  await expect(page).toHaveURL(/\/reset-password$/)
  expect(submissions.at(-1)).toEqual({ path: '/api/v1/auth/reset-password', body: { token: 'reset-test-token', newPassword: 'changed-password-456' } })
})

test('login distinguishes unverified, disabled and invalid credentials without exposing a session', async ({ page }) => {
  let code = 'email_not_verified'
  await page.route('**/api/v1/**', (route) => route.fulfill({ status: code === 'invalid_credentials' ? 401 : 403, contentType: 'application/json', body: JSON.stringify({ code, message: code }) }))
  await page.goto('/login')
  await page.getByLabel('邮箱').fill('writer@example.com'); await page.getByLabel('密码').fill('test-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('请先验证邮箱，再登录工作台。')
  await expect(page.getByRole('link', { name: '重新发送验证邮件' })).toBeVisible()
  code = 'account_disabled'; await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('账号已停用，请联系团队管理员。')
  code = 'invalid_credentials'; await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('邮箱或密码不正确，请检查后重试。')
  expect(await page.evaluate(() => sessionStorage.getItem('storyboard-web.session'))).toBeNull()
})

test('password reset clears a previously signed-in browser session before returning to login', async ({ page }) => {
  await authenticated(page)
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.endsWith('/auth/me') ? profile : { message: '密码已重置。' }) })
  })
  await page.goto('/reset-password?token=reset-signed-in-token')
  await page.getByLabel('新密码', { exact: true }).fill('changed-password-456')
  await page.getByLabel('确认新密码').fill('changed-password-456')
  await page.getByRole('button', { name: '重置密码', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('密码已重置。')
  expect(await page.evaluate(() => sessionStorage.getItem('storyboard-web.session'))).toBeNull()
  await page.getByRole('link', { name: '使用新密码登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
})

test('non-admin profile cannot mount admin pages or make admin requests', async ({ page }) => {
  await authenticated(page); let adminRequests = 0
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.includes('/admin/')) adminRequests++
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(path.endsWith('/auth/me') ? { ...profile, roles: ['USER'], permissions: [] } : []) })
  })
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: '没有后台管理权限' })).toBeVisible()
  expect(adminRequests).toBe(0)
  await page.locator('.account-menu summary').click()
  await expect(page.getByRole('link', { name: '后台管理', exact: true })).toHaveCount(0)
})

test('admin manages user roles and status, inspects runs and distinguishes missing or mock cost', async ({ page }, testInfo) => {
  await authenticated(page)
  const roles = structuredClone(baseRoles)
  const users = [{ id: 1, email: profile.email, fullName: profile.fullName, enabled: true, emailVerified: true, roles: [{ id: 1, name: 'ADMIN' }], self: true }, { id: 2, email: 'writer@example.com', fullName: '编剧甲', enabled: true, emailVerified: true, roles: [{ id: 2, name: 'USER' }], self: false }]
  const requests: Array<{ path: string; method: string; body: unknown; search: string }> = []
  const run = { id: 'run-failed', status: 'failed', taskType: 'generate_storyboard', userId: 2, userName: '编剧甲', userEmail: 'writer@example.com', projectId: 2, projectName: '雨夜短片', scriptId: 3, createdAt: '2026-10-08T12:00:00Z', startedAt: '2026-10-08T12:00:01Z', endedAt: '2026-10-08T12:00:10Z', errorCode: 'storyboard_source_quote_invalid', resultType: null, resultId: null, resultStoryboardId: null, usage: unknownUsage }
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname; const method = request.method(); const body = request.postData() ? request.postDataJSON() : undefined
    requests.push({ path, method, body, search: url.search })
    const reply = (value: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    if (path.endsWith('/auth/me')) return reply(profile)
    if (path.endsWith('/admin/permissions')) return reply([permission])
    if (path.endsWith('/admin/roles') && method === 'POST') { const role = { id: 3, ...body, system: false }; roles.push(role); return reply(role) }
    if (path.endsWith('/admin/roles')) return reply(roles)
    if (path.endsWith('/admin/users/2/status')) { users[1].enabled = body.enabled; return reply(users[1]) }
    if (path.endsWith('/admin/users/2/roles')) { users[1].roles = roles.filter((role) => body.roleIds.includes(role.id)).map(({ id, name }) => ({ id, name })); return reply(users[1]) }
    if (path.endsWith('/admin/users')) {
      const q = url.searchParams.get('q') ?? ''; const matches = users.filter((user) => `${user.fullName} ${user.email}`.includes(q))
      return reply({ items: matches, page: Number(url.searchParams.get('page') ?? 0), size: 20, totalElements: matches.length, totalPages: 1 })
    }
    if (path.endsWith('/admin/runs/run-failed')) return reply(run)
    if (path.endsWith('/admin/runs')) return reply({ items: [run, { ...run, id: 'run-mock', status: 'completed', errorCode: null, usage: { ...unknownUsage, mode: 'mock', costStatus: 'not_applicable' } }], page: 0, size: 20, totalElements: 2, totalPages: 1 })
    if (path.endsWith('/admin/usage')) return reply({ totalRuns: 4, liveRuns: 1, mockRuns: 2, unknownRuns: 1, providerRequests: 3, inputTokens: 500, outputTokens: 200, totalTokens: 700, estimatedCostUsd: 0.0012, costStatus: 'partial' })
    return reply({ message: `Unexpected ${method} ${path}` }, 500)
  })
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: '用户管理', exact: true })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: 'admin@example.com' }).getByRole('button', { name: '停用', exact: true })).toBeDisabled()
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.screenshot({ path: testInfo.outputPath('admin-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: testInfo.outputPath('admin-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('link', { name: '角色权限', exact: true }).click()
  await expect(page.getByText('系统角色 · 只读')).toHaveCount(2)
  await page.getByRole('button', { name: '创建角色', exact: true }).click()
  await page.getByLabel('角色名称').fill('reviewer')
  await page.getByRole('checkbox', { name: /查看所有用户/ }).check()
  await page.getByRole('button', { name: '保存角色权限' }).click()
  await expect(page.getByRole('status')).toContainText('角色权限已保存')
  expect(roles[2].name).toBe('REVIEWER')
  await page.getByRole('link', { name: '用户管理', exact: true }).click()
  await page.getByLabel('搜索用户').fill('writer@example.com'); await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'admin@example.com' })).toHaveCount(0)
  await page.getByRole('button', { name: '分配角色' }).click()
  await page.getByRole('checkbox', { name: /REVIEWER/ }).check(); await page.getByRole('button', { name: '保存角色', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('用户角色已更新。')
  expect(users[1].roles.map((role) => role.name)).toEqual(['USER', 'REVIEWER'])
  page.once('dialog', (dialog) => dialog.accept()); await page.getByRole('button', { name: '停用', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('用户已停用。')
  await page.getByRole('button', { name: '启用', exact: true }).click(); await expect(page.getByRole('status')).toHaveText('用户已启用。')
  await page.getByRole('link', { name: '生成任务', exact: true }).click()
  await expect(page.getByRole('cell', { name: '未记录', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '不适用', exact: true })).toBeVisible()
  await page.getByLabel('任务状态').selectOption('failed'); await page.getByLabel('任务类型').selectOption('generate_storyboard')
  await page.getByRole('button', { name: '查看详情' }).first().click()
  await expect(page.getByRole('region', { name: '任务详情' })).toContainText('生成结果中的原文引用与剧本不一致')
  await page.getByRole('link', { name: '用量记录', exact: true }).click()
  await expect(page.getByText('USD 0.001200（部分记录）')).toBeVisible()
  await expect(page.getByText(/估算不代表供应商实际扣款/)).toBeVisible()
  expect(requests.some((request) => request.path.endsWith('/admin/users') && request.search.includes('q=writer%40example.com'))).toBe(true)
  expect(requests.some((request) => request.path.endsWith('/admin/runs') && request.search.includes('status=failed') && request.search.includes('taskType=generate_storyboard'))).toBe(true)
  expect(requests.some((request) => /\/generations|\/regenerations/.test(request.path))).toBe(false)
})

test('concurrent expired profile and project reads refresh once and account logout clears the session', async ({ page }) => {
  await authenticated(page); let refreshes = 0; let logouts = 0
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(); const path = new URL(request.url()).pathname
    const reply = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path.endsWith('/auth/refresh')) { refreshes++; return reply({ accessToken: 'fresh-access', refreshToken: 'fresh-refresh' }) }
    if (path.endsWith('/auth/logout')) { logouts++; return route.fulfill({ status: 204 }) }
    if (request.headers().authorization !== 'Bearer fresh-access') return reply({ code: 'invalid_credentials' }, 401)
    if (path.endsWith('/auth/me')) return reply(profile)
    if (path.endsWith('/screenplay/projects')) return reply([])
    return reply({}, 404)
  })
  await page.goto('/projects')
  await expect(page.getByRole('heading', { name: '还没有项目' })).toBeVisible()
  await expect(page.locator('.account-menu summary')).toContainText('团队管理员')
  expect(refreshes).toBe(1)
  await page.locator('.account-menu summary').click(); await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  expect(logouts).toBe(1)
  expect(await page.evaluate(() => sessionStorage.getItem('storyboard-web.session'))).toBeNull()
})
