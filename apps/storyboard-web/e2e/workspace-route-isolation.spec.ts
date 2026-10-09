import { expect, test, type Page } from '@playwright/test'

const workspace = (script: string) => `/projects/project-route/scripts/${script}/storyboard`
const scripts = ['script-a', 'script-b'].map((id) => ({ id, projectId: 'project-route', versionName: id === 'script-a' ? '有分镜的剧本 A' : '尚无分镜的剧本 B', originalFilename: `${id}.txt`, rawText: `${id} 原文。`, status: 'ANALYZED' }))
const scene = (script: string) => ({ id: `scene-${script}`, scriptVersionId: script, sceneNo: 1, heading: script === 'script-a' ? 'A 场景' : 'B 首次创作场景', rawText: `${script} 原文。`, summary: '', sortOrder: 0 })
const board = {
  id: 'board-a', projectId: 'project-route', createdBy: 'user-route', sourceRunId: 'historical-run-a', revision: 1,
  createdAt: '2026-10-09T00:00:00Z', updatedAt: '2026-10-09T00:00:00Z',
  sourceSnapshot: { scriptId: 'script-a', scriptRevision: 1, sourceSceneId: 'scene-script-a', sceneNo: 1, heading: 'A 已保存场景', sceneText: 'script-a 原文。', sceneHash: 'hash-a' },
  shots: [{ id: 'shot-a', orderIndex: 0, shotSize: 'MEDIUM', cameraMovement: 'STATIC', visualDescription: '仅属于 A 的镜头', dialogue: '', sound: '', durationSeconds: 3, imagePrompt: 'image A', videoPrompt: 'video A', sourceQuote: 'script-a 原文。' }],
  proposalSummaries: []
}

async function installApiIsolation(page: Page) {
  const generationBodies: Array<Record<string, unknown>> = []
  const unexpected: string[] = []
  await page.addInitScript(() => sessionStorage.setItem('storyboard-web.session', JSON.stringify({ accessToken: 'route-test-access', refreshToken: 'route-test-refresh' })))
  // Every API request terminates here, including generation. Nothing reaches
  // Java, a Gateway, a database, or a model provider.
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (request.method() === 'GET') {
      if (path.endsWith('/auth/me')) return json({ id: 71, email: 'route@example.test', fullName: '隔离测试成员', roles: ['USER'], permissions: [], organizationId: 81, organizationName: '测试工作区', enabled: true, emailVerified: true })
      if (path.endsWith('/screenplay/projects')) return json([{ id: 'project-route', name: '路径隔离项目', description: '', genre: '短片', status: 'ACTIVE' }])
      if (path.endsWith('/projects/project-route/scripts')) return json(scripts)
      if (path.endsWith('/scripts/script-a/scenes')) return json([scene('script-a')])
      if (path.endsWith('/scripts/script-b/scenes')) return json([scene('script-b')])
      if (path.endsWith('/projects/project-route/storyboards')) return json({ items: url.searchParams.get('scriptId') === 'script-a' ? [{ id: board.id, projectId: board.projectId, scriptId: 'script-a', scriptRevision: 1, sceneNo: 1, heading: board.sourceSnapshot.heading, revision: 1, shotCount: 1, createdAt: board.createdAt, updatedAt: board.updatedAt }] : [] })
      if (path.endsWith('/screenplay/storyboards/board-a')) return json(board)
    }
    if (request.method() === 'POST' && path.endsWith('/projects/project-route/storyboards/generations')) {
      generationBodies.push(request.postDataJSON())
      return json({ message: '测试已拦截生成请求，未调用模型。' }, 503)
    }
    unexpected.push(`${request.method()} ${path}`)
    return json({ message: 'Unexpected test API request' }, 500)
  })
  return { generationBodies, unexpected }
}

async function buildWorkspaceHistory(page: Page) {
  // Create real router history B -> versions -> A, then history.go(-2) jumps
  // between two matches of the SAME workspace route without rendering versions.
  await page.goto(workspace('script-b'))
  await expect(page.getByRole('heading', { name: 'B 首次创作场景' })).toBeVisible()
  await page.getByRole('link', { name: '剧本版本', exact: true }).click()
  await page.getByRole('link', { name: '进入分镜 · 有分镜的剧本 A', exact: true }).click()
  await expect(page.getByLabel('画面描述')).toHaveValue('仅属于 A 的镜头')
}

async function expectCleanB(page: Page) {
  await expect(page).toHaveURL(new RegExp(`${workspace('script-b')}$`))
  await expect(page.getByRole('heading', { name: 'B 首次创作场景' })).toBeVisible()
  await expect(page.getByLabel('画面描述')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'A 已保存场景' })).toHaveCount(0)
  await expect(page.locator('.shot-card')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '生成分镜', exact: true })).toBeEnabled()
}

test('response injected: changing script paths clears previous shots and generates only from the new scene', async ({ page }) => {
  const mock = await installApiIsolation(page)
  await buildWorkspaceHistory(page)
  await page.evaluate(() => history.go(-2))
  await expectCleanB(page)
  await page.getByLabel('镜头数').selectOption('4')
  await page.getByRole('button', { name: '生成分镜', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('测试已拦截生成请求')
  expect(mock.generationBodies).toHaveLength(1)
  expect(mock.generationBodies[0]).toMatchObject({ scriptId: 'script-b', sceneId: 'scene-script-b', targetShotCount: 4 })
  expect(mock.unexpected).toEqual([])
})

test('response injected: dirty cross-script history stays on the original edit until confirmed', async ({ page }) => {
  const mock = await installApiIsolation(page)
  await buildWorkspaceHistory(page)
  await page.getByLabel('画面描述').fill('A 尚未保存的编辑')
  let confirmations = 0
  page.once('dialog', async (dialog) => { confirmations += 1; await dialog.dismiss() })
  await page.evaluate(() => history.go(-2))
  await expect.poll(() => confirmations).toBe(1)
  await expect(page).toHaveURL(new RegExp(`${workspace('script-a')}$`))
  await expect(page.getByLabel('画面描述')).toHaveValue('A 尚未保存的编辑')
  await expect(page.getByText('未保存', { exact: true })).toBeVisible()
  page.once('dialog', async (dialog) => { confirmations += 1; await dialog.accept() })
  await page.evaluate(() => history.go(-2))
  await expectCleanB(page)
  expect(confirmations).toBe(2)
  expect(mock.generationBodies).toEqual([])
  expect(mock.unexpected).toEqual([])
})
