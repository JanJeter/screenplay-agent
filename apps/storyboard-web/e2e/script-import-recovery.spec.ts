import { expect, test, type Page } from '@playwright/test'

const profile = { id: 1, email: 'writer@example.com', fullName: '测试编剧', roles: ['USER'], permissions: [], organizationId: 1, organizationName: '测试工作区', enabled: true, emailVerified: true }
const source = 'INT. 测试车站 - 夜\n\n林舟握着尚未拆开的信封。\n许晴：“等雨停了再走。”'
type SavedScript = { id: string; projectId: string; versionName: string; originalFilename: string; rawText: string; status: string }

async function installScriptApi(page: Page, options: { failFirstCreate?: boolean; projectUnavailable?: boolean } = {}) {
  const state = {
    scripts: [{ id: 'script-old', projectId: 'project-1', versionName: '已有版本', originalFilename: 'old.txt', rawText: '已有版本的原文。', status: 'ANALYZED' }] as SavedScript[],
    createCalls: [] as Array<Record<string, string>>,
    analyzeCalls: [] as string[],
    listCalls: 0,
    unexpected: [] as string[]
  }
  await page.addInitScript(() => sessionStorage.setItem('storyboard-web.session', JSON.stringify({ accessToken: 'test-access', refreshToken: 'test-refresh' })))
  // Every API request is fulfilled here. Unexpected routes are aborted, never
  // passed through to a real backend or a model generation endpoint.
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(); const path = new URL(request.url()).pathname; const method = request.method()
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (method === 'GET' && path === '/api/v1/auth/me') return json(profile)
    if (method === 'GET' && path === '/api/v1/screenplay/projects') return options.projectUnavailable ? json({ message: '项目名称暂不可用' }, 500) : json([{ id: 'project-1', name: '导入恢复测试', description: '', genre: '短片', status: 'ACTIVE' }])
    if (path === '/api/v1/screenplay/projects/project-1/scripts' && method === 'GET') { state.listCalls++; return json(state.scripts) }
    if (path === '/api/v1/screenplay/projects/project-1/scripts' && method === 'POST') {
      const body = request.postDataJSON() as Record<string, string>
      state.createCalls.push(body)
      if (options.failFirstCreate && state.createCalls.length === 1) return json({ code: 'test_create_failed', message: '版本暂时无法保存，请重试。' }, 500)
      const script: SavedScript = { id: 'script-new', projectId: 'project-1', versionName: body.versionName, originalFilename: body.originalFilename, rawText: body.rawText, status: 'UPLOADED' }
      state.scripts.push(script)
      return json(script, 201)
    }
    if (path === '/api/v1/screenplay/scripts/script-new/analyze' && method === 'POST') {
      state.analyzeCalls.push(path)
      const script = state.scripts.find((item) => item.id === 'script-new')!
      if (state.analyzeCalls.length === 1) { script.status = 'ANALYZE_FAILED'; return json({ code: 'test_analysis_failed', message: '场景解析暂时失败，请重试。' }, 500) }
      script.status = 'ANALYZED'
      return route.fulfill({ status: 204 })
    }
    state.unexpected.push(method + ' ' + path)
    await route.abort('blockedbyclient')
  })
  await page.goto('/projects/project-1/scripts')
  await expect(page.getByRole('heading', { name: '已有版本', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '粘贴剧本', exact: true }).click()
  await page.getByLabel('版本名称', { exact: true }).fill('导入恢复新版本')
  await page.getByLabel('文件名', { exact: true }).fill('recovery.txt')
  await page.getByRole('textbox', { name: '剧本文本', exact: true }).fill(source)
  return state
}

test('failed script creation preserves editable input; cancelling after a saved parse failure opens that saved version', async ({ page }) => {
  const state = await installScriptApi(page, { failFirstCreate: true, projectUnavailable: true })
  await page.getByRole('button', { name: '保存并解析', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('版本暂时无法保存，请重试。')
  await expect(page.getByLabel('版本名称', { exact: true })).toHaveValue('导入恢复新版本')
  await expect(page.getByLabel('文件名', { exact: true })).toHaveValue('recovery.txt')
  await expect(page.getByRole('textbox', { name: '剧本文本', exact: true })).toHaveValue(source)
  await expect(page.getByRole('textbox', { name: '剧本文本', exact: true })).toBeEditable()
  expect(state.createCalls).toHaveLength(1)
  expect(state.analyzeCalls).toHaveLength(0)

  let navigationBlocked = false
  page.once('dialog', async (dialog) => { navigationBlocked = true; await dialog.dismiss() })
  await page.locator('.app-rail').getByRole('link', { name: '项目', exact: true }).click()
  expect(navigationBlocked).toBe(true)
  await expect(page).toHaveURL(/\/projects\/project-1\/scripts$/)
  await expect(page.getByRole('textbox', { name: '剧本文本', exact: true })).toHaveValue(source)

  await page.getByRole('button', { name: '保存并解析', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('场景解析暂时失败，请重试。')
  await expect(page.getByRole('button', { name: '重试解析', exact: true })).toBeEnabled()
  const listsBeforeCancel = state.listCalls
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('heading', { name: '导入恢复新版本', exact: true })).toBeVisible()
  await expect(page.locator('.script-original')).toHaveText(source)
  await expect(page.locator('.script-version.active .script-version__select')).toContainText('导入恢复新版本')
  await expect(page.getByRole('link', { name: '进入分镜 · 导入恢复新版本', exact: true })).toHaveAttribute('href', '/projects/project-1/scripts/script-new/storyboard')
  expect(state.listCalls).toBeGreaterThan(listsBeforeCancel)
  expect(state.createCalls).toHaveLength(2)
  expect(state.analyzeCalls).toHaveLength(1)
  expect(state.scripts).toHaveLength(2)
  expect(state.unexpected).toEqual([])
})

test('retry after create succeeds and analysis fails locks original input and parses the same script without another create', async ({ page }) => {
  const state = await installScriptApi(page)
  await page.getByRole('button', { name: '保存并解析', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('场景解析暂时失败，请重试。')
  for (const label of ['版本名称', '文件名', '剧本文本']) {
    await expect(page.getByRole('textbox', { name: label, exact: true })).toHaveJSProperty('readOnly', true)
    await expect(page.getByRole('textbox', { name: label, exact: true })).not.toBeEditable()
  }
  await expect(page.getByRole('textbox', { name: '剧本文本', exact: true })).toHaveValue(source)
  expect(state.createCalls).toEqual([{ versionName: '导入恢复新版本', originalFilename: 'recovery.txt', rawText: source }])
  expect(state.analyzeCalls).toHaveLength(1)
  await page.getByRole('button', { name: '重试解析', exact: true }).click()
  await expect(page.getByRole('heading', { name: '导入恢复新版本', exact: true })).toBeVisible()
  await expect(page.locator('.script-original')).toHaveText(source)
  await expect(page.locator('.script-version.active')).toContainText('已解析')
  await expect(page.getByRole('button', { name: '粘贴剧本', exact: true })).toBeEnabled()
  expect(state.createCalls).toHaveLength(1)
  expect(state.analyzeCalls).toEqual(['/api/v1/screenplay/scripts/script-new/analyze', '/api/v1/screenplay/scripts/script-new/analyze'])
  expect(state.scripts).toHaveLength(2)
  expect(state.unexpected).toEqual([])
})
