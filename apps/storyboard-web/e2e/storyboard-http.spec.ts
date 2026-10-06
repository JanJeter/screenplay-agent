import { expect, test, type Page } from '@playwright/test'

const sourceText = `INT. 旧公寓客厅 - 夜

雨敲着窗。许晴站在门边，没有进来。

林舟从抽屉里拿出一封牛皮纸信，信封完整，封口未拆。

许晴：你说有东西要给我。

林舟把信藏到身后，停了两秒，又把它放到茶几上。

林舟：不是现在。

许晴走到茶几前，拿起那封未拆的信。她没有打开，只看着林舟。`

type Shot = { id: string; orderIndex: number; visualDescription: string }
type Storyboard = { id: string; revision: number; shots: Shot[] }

async function browserApi<T>(page: Page, path: string): Promise<T> {
  const baseUrl = process.env.E2E_JAVA_URL ?? 'http://127.0.0.1:18080'
  return page.evaluate(async ({ requestPath, requestBaseUrl }) => {
    const session = JSON.parse(sessionStorage.getItem('storyboard-web.session') ?? '{}') as { accessToken?: string }
    const response = await fetch(`${requestBaseUrl}${requestPath}`, { headers: { Authorization: `Bearer ${session.accessToken ?? ''}` } })
    if (!response.ok) throw new Error(`${requestPath}: HTTP ${response.status}`)
    return response.json() as Promise<T>
  }, { requestPath: path, requestBaseUrl: baseUrl })
}

test('real HTTP workflow preserves source and non-target shots', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('邮箱').fill('admin@demo.com')
  await page.getByLabel('密码').fill('admin12345')
  await page.getByRole('button', { name: '登录' }).click()

  await page.getByRole('button', { name: '创建项目' }).first().click()
  await page.getByLabel('项目名称').fill('SB-11 HTTP 验收')
  await page.getByLabel('类型').fill('悬疑短片')
  await page.getByRole('button', { name: '创建并导入剧本' }).click()

  await page.getByRole('button', { name: '粘贴剧本' }).click()
  await page.getByLabel('版本名称').fill('SB-11 第一稿')
  await page.getByLabel('剧本文本').fill(sourceText)
  await page.getByRole('button', { name: '保存并解析' }).click()
  await page.getByRole('link', { name: /SB-11 第一稿/ }).click()

  await expect(page.getByRole('button', { name: '生成分镜' })).toBeVisible()
  await page.getByLabel('镜头数').selectOption('4')
  await page.getByLabel('创作要求').fill('保持信封未拆、人物克制，使用中文。')
  await page.getByRole('button', { name: '生成分镜' }).click()
  await expect(page.locator('.shot-card')).toHaveCount(4, { timeout: 45_000 })
  await expect(page.getByText('分镜已保存，可继续编辑。')).toBeVisible()

  const scriptId = new URL(page.url()).pathname.split('/')[4]
  const storyboardId = new URL(page.url()).searchParams.get('storyboard')
  expect(storyboardId).not.toBeNull()
  const before = await browserApi<Storyboard>(page, `/api/v1/screenplay/storyboards/${storyboardId}`)
  const script = await browserApi<{ rawText: string }>(page, `/api/v1/screenplay/scripts/${scriptId}`)
  expect(script.rawText).toBe(sourceText)

  const editedDescription = '验收编辑：林舟把未拆的信放在茶几上，许晴仍未打开。'
  await page.getByLabel('画面描述').fill(editedDescription)
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByText('已保存为 r2。')).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('画面描述')).toHaveValue(editedDescription)

  const afterSave = await browserApi<Storyboard>(page, `/api/v1/screenplay/storyboards/${storyboardId}`)
  expect(afterSave.revision).toBe(2)
  expect(afterSave.shots[0].visualDescription).toBe(editedDescription)
  expect(afterSave.shots.slice(1)).toEqual(before.shots.slice(1))

  await page.getByRole('button', { name: '重做此镜头' }).click()
  await page.getByLabel('修改要求').fill('调整为更克制的静态构图，信封仍不能拆开。')
  await page.getByRole('button', { name: '生成候选' }).click()
  await expect(page.getByText('候选镜头已保存；原镜头尚未修改。请比较后采纳或放弃。')).toBeVisible({ timeout: 45_000 })
  await page.getByRole('button', { name: '采纳候选' }).click()
  await expect(page.getByText('候选已采纳，只更新了当前镜头。')).toBeVisible()

  const afterAccept = await browserApi<Storyboard>(page, `/api/v1/screenplay/storyboards/${storyboardId}`)
  expect(afterAccept.revision).toBe(3)
  expect(afterAccept.shots[0].id).toBe(afterSave.shots[0].id)
  expect(afterAccept.shots[0].orderIndex).toBe(afterSave.shots[0].orderIndex)
  expect(afterAccept.shots.slice(1)).toEqual(afterSave.shots.slice(1))
  const sourceAfterAccept = await browserApi<{ rawText: string }>(page, `/api/v1/screenplay/scripts/${scriptId}`)
  expect(sourceAfterAccept.rawText).toBe(sourceText)

  // This request reaches the temporary Java/PostgreSQL stack first. Only its
  // browser response is held back, reproducing a user typing B after A was
  // submitted but before the real response became visible to React.
  let releaseSaveResponse: (() => void) | undefined
  let markSaveReceived: (() => void) | undefined
  const saveResponseReleased = new Promise<void>((resolve) => { releaseSaveResponse = resolve })
  const saveReceived = new Promise<void>((resolve) => { markSaveReceived = resolve })
  const saveUrl = `**/api/v1/screenplay/storyboards/${storyboardId}`
  await page.route(saveUrl, async (route) => {
    if (route.request().method() !== 'PUT') { await route.continue(); return }
    const response = await route.fetch()
    markSaveReceived?.()
    await saveResponseReleased
    await route.fulfill({ response })
  })
  const saveA = '真实 HTTP 保存快照 A。'
  const localB = '真实 HTTP 响应等待期间输入 B。'
  await page.getByLabel('画面描述').fill(saveA)
  await page.getByRole('button', { name: '保存修改' }).click()
  await saveReceived
  await page.getByLabel('画面描述').fill(localB)
  releaseSaveResponse?.()
  await expect(page.getByText(/后续输入仍未保存/)).toBeVisible()
  await expect(page.getByLabel('画面描述')).toHaveValue(localB)
  await expect(page.getByRole('button', { name: '保存修改' })).toBeEnabled()
  await page.unroute(saveUrl)
  const afterDelayedSave = await browserApi<Storyboard>(page, `/api/v1/screenplay/storyboards/${storyboardId}`)
  expect(afterDelayedSave.revision).toBe(4)
  expect(afterDelayedSave.shots[0].visualDescription).toBe(saveA)

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 Markdown' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/^分镜-场1\.md$/)
  const markdown = await (await download.createReadStream())?.toArray()
  const exported = Buffer.concat(markdown ?? []).toString('utf8')
  expect(exported).toContain('# 分镜表')
  expect(exported).toContain(saveA)
})
