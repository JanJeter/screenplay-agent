import { expect, test } from '@playwright/test'

test('archived Java environment allows login and reading but blocks business writes before HTTP', async ({ page }) => {
  // Keep the real environment detection and API guard; only inject the old
  // Vite setting so this test never reaches a historical database or Gateway.
  await page.route('**/src/environment.ts*', async (route) => {
    const response = await route.fetch()
    const body = await response.text()
    expect(body).toMatch(/export const javaApiBase =[^\n]+/)
    await route.fulfill({ response, body: body.replace(/export const javaApiBase =[^\n]+/, 'export const javaApiBase = "http://127.0.0.1:18083";') })
  })
  let logins = 0; let reads = 0; let businessWrites = 0
  const board = {
    id: 'legacy-board', projectId: 'legacy-project', createdBy: 'user-1', sourceRunId: 'old-run', revision: 1,
    createdAt: '2026-10-08T00:00:00Z', updatedAt: '2026-10-08T00:00:00Z',
    sourceSnapshot: { scriptId: 'legacy-script', scriptRevision: 1, sourceSceneId: 'legacy-scene', sceneNo: 1, heading: '历史场景', sceneText: '信封未拆。', sceneHash: 'hash' },
    shots: [{ id: 'legacy-shot', orderIndex: 0, shotSize: 'MEDIUM', cameraMovement: 'STATIC', visualDescription: '历史分镜内容', dialogue: '', sound: '', durationSeconds: 4, imagePrompt: 'image', videoPrompt: 'video', sourceQuote: '信封未拆。' }], proposalSummaries: []
  }
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(); const path = new URL(request.url()).pathname
    const reply = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path.endsWith('/auth/login')) { logins += 1; return reply({ accessToken: 'test-access', refreshToken: 'test-refresh' }) }
    if (request.method() !== 'GET') { businessWrites += 1; return reply({ message: 'Unexpected write to archive' }, 500) }
    reads += 1
    if (path.endsWith('/screenplay/projects')) return reply([{ id: 'legacy-project', name: '历史项目', description: '', genre: '短片', status: 'ACTIVE' }])
    if (path.endsWith('/scenes')) return reply([{ id: 'legacy-scene', scriptVersionId: 'legacy-script', sceneNo: '1', heading: '历史场景', rawText: '信封未拆。', sortOrder: 0 }])
    if (path.endsWith('/storyboards')) return reply({ items: [{ id: board.id, projectId: board.projectId, scriptId: 'legacy-script', scriptRevision: 1, sceneNo: 1, heading: '历史场景', revision: 1, shotCount: 1, createdAt: board.createdAt, updatedAt: board.updatedAt }] })
    if (path.endsWith('/storyboards/legacy-board')) return reply(board)
    return reply({ message: 'Unhandled read' }, 500)
  })

  await page.goto('/login')
  await expect(page.getByText('历史测试环境 · 只读')).toBeVisible()
  const newWorkbench = page.getByRole('link', { name: '打开新工作台' })
  await expect(newWorkbench).toHaveAttribute('href', 'http://127.0.0.1:5174/')
  await expect(newWorkbench).toHaveAttribute('target', '_blank')
  await page.getByLabel('邮箱').fill('test@example.com')
  await page.getByLabel('密码').fill('test-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('link', { name: /历史项目/ })).toBeVisible()
  expect(logins).toBe(1)
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await page.getByLabel('项目名称').fill('不可写入历史库')
  await page.getByRole('button', { name: '创建并导入剧本' }).click()
  await expect(page.getByRole('alert')).toContainText('这是封存的历史测试环境，只能查看。')
  expect(businessWrites).toBe(0)

  await page.goto('/projects/legacy-project/scripts/legacy-script/storyboard?storyboard=legacy-board')
  await expect(page.getByLabel('画面描述')).toHaveValue('历史分镜内容')
  await page.getByLabel('画面描述').fill('本地输入应保留')
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.locator('.editor-notice')).toContainText('这是封存的历史测试环境，只能查看。')
  await expect(page.getByLabel('画面描述')).toHaveValue('本地输入应保留')
  await expect(page.getByText('未保存', { exact: true })).toBeVisible()
  expect(businessWrites).toBe(0)
  expect(reads).toBeGreaterThan(0)
})
