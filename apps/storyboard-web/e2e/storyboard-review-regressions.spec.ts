import { expect, test, type Page, type Route } from '@playwright/test'

type Board = ReturnType<typeof board>
const shot = (id: string, visualDescription: string) => ({ id, orderIndex: 0, shotSize: 'MEDIUM', cameraMovement: 'STATIC', visualDescription, dialogue: '', sound: '', durationSeconds: 3, imagePrompt: 'image prompt', videoPrompt: 'video prompt', sourceQuote: '原文。' })
function board(id: string, heading: string, visualDescription: string) { return { id, projectId: 'project-1', createdBy: 'user-1', sourceRunId: `run-${id}`, revision: 1, createdAt: '2026-10-06T00:00:00Z', updatedAt: '2026-10-06T00:00:00Z', sourceSnapshot: { scriptId: 'script-1', scriptRevision: 1, sourceSceneId: 'scene-1', sceneNo: id === 'storyboard-a' ? 1 : 2, heading, sceneText: '原文。', sceneHash: 'hash' }, shots: [shot(`shot-${id}`, visualDescription)], proposalSummaries: [] } }
const scene = { id: 'scene-1', scriptVersionId: 'script-1', sceneNo: 1, heading: '场景', rawText: '原文。', summary: '测试', sortOrder: 0 }
const summary = (value: Board) => ({ id: value.id, projectId: value.projectId, scriptId: 'script-1', scriptRevision: 1, sceneNo: value.sourceSnapshot.sceneNo, heading: value.sourceSnapshot.heading, revision: value.revision, shotCount: value.shots.length, createdAt: value.createdAt, updatedAt: value.updatedAt })
async function json(route: Route, value: unknown, status = 200) { await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) }) }
async function session(page: Page) { await page.addInitScript(() => sessionStorage.setItem('storyboard-web.session', JSON.stringify({ accessToken: 'test-access', refreshToken: 'test-refresh' }))) }

async function installBoards(page: Page, options: { completedRun?: boolean; delaySave?: 'success' | 'conflict' } = {}) {
  const boards = { a: board('storyboard-a', '工作 A', 'A 原内容'), b: board('storyboard-b', '工作 B', 'B 原内容') }
  let releaseB: (() => void) | undefined; let bRequested: (() => void) | undefined; let releaseSave: (() => void) | undefined; let saveRequested: (() => void) | undefined
  const bGate = new Promise<void>((resolve) => { releaseB = resolve }); const waitForB = new Promise<void>((resolve) => { bRequested = resolve })
  const saveGate = new Promise<void>((resolve) => { releaseSave = resolve }); const waitForSave = new Promise<void>((resolve) => { saveRequested = resolve })
  await session(page)
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(); const path = new URL(request.url()).pathname
    if (path.endsWith('/screenplay/scripts/script-1/scenes')) return json(route, [scene])
    if (path.endsWith('/screenplay/projects/project-1/storyboards')) return json(route, { items: [summary(boards.a), summary(boards.b)] })
    if (path.endsWith('/screenplay/agent/runs/run-completed')) return json(route, { id: 'run-completed', status: 'COMPLETED', errorCode: null, resultRef: { type: 'storyboard', id: boards.b.id, storyboardId: boards.b.id } })
    if (path.endsWith('/screenplay/storyboards/storyboard-a') && request.method() === 'PUT') {
      saveRequested?.(); await saveGate
      if (options.delaySave === 'conflict') return json(route, { code: 'STORYBOARD_REVISION_CONFLICT', message: 'stale' }, 409)
      boards.a = { ...boards.a, revision: 2, shots: request.postDataJSON().shots.map((value: typeof boards.a.shots[number], orderIndex: number) => ({ ...value, orderIndex })) }
      return json(route, boards.a)
    }
    if (path.endsWith('/screenplay/storyboards/storyboard-a')) return json(route, boards.a)
    if (path.endsWith('/screenplay/storyboards/storyboard-b')) { if (options.completedRun) { bRequested?.(); await bGate }; return json(route, boards.b) }
    return json(route, { message: `Unhandled ${request.method()} ${path}` }, 500)
  })
  return { boards, releaseB: () => releaseB?.(), waitForB, releaseSave: () => releaseSave?.(), waitForSave }
}

async function open(page: Page, suffix = '') { await page.goto(`/projects/project-1/scripts/script-1/storyboard?storyboard=storyboard-a${suffix}`); await expect(page.getByLabel('画面描述')).toHaveValue('A 原内容') }
async function changeToB(page: Page) { await page.getByRole('button', { name: /场 2/ }).click(); await expect(page.getByRole('heading', { name: '工作 B' })).toBeVisible(); await expect(page).toHaveURL(/storyboard=storyboard-b/) }

test('response injected F1: history switches between storyboards only after confirmation', async ({ page }) => {
  await installBoards(page); await open(page); await changeToB(page)
  await page.getByLabel('画面描述').fill('B 未保存编辑')
  page.once('dialog', (dialog) => dialog.dismiss()); await page.evaluate(() => history.back())
  await expect(page).toHaveURL(/storyboard=storyboard-b/); await expect(page.getByRole('heading', { name: '工作 B' })).toBeVisible(); await expect(page.getByLabel('画面描述')).toHaveValue('B 未保存编辑'); await expect(page.getByText('未保存', { exact: true })).toBeVisible()
})

test('response injected F1: forward storyboard navigation also waits for confirmation', async ({ page }) => {
  await installBoards(page); await open(page); await changeToB(page)
  await page.evaluate(() => history.back()); await expect(page).toHaveURL(/storyboard=storyboard-a/); await expect(page.getByRole('heading', { name: '工作 A' })).toBeVisible()
  await page.getByLabel('画面描述').fill('A 未保存编辑')
  page.once('dialog', (dialog) => dialog.dismiss()); await page.evaluate(() => history.forward())
  await expect(page).toHaveURL(/storyboard=storyboard-a/); await expect(page.getByLabel('画面描述')).toHaveValue('A 未保存编辑')
  page.once('dialog', (dialog) => dialog.accept()); await page.evaluate(() => history.forward())
  await expect(page).toHaveURL(/storyboard=storyboard-b/); await expect(page.getByRole('heading', { name: '工作 B' })).toBeVisible()
})

test('response injected F1: default storyboard entry also blocks browser back', async ({ page }) => {
  await installBoards(page)
  await page.goto('/projects/project-1/scripts/script-1/storyboard')
  await expect(page.getByLabel('画面描述')).toHaveValue('A 原内容')
  await changeToB(page)
  await page.getByLabel('画面描述').fill('默认入口返回前的 B 编辑')

  let cancelConfirmations = 0
  page.once('dialog', (dialog) => { cancelConfirmations += 1; void dialog.dismiss().catch(() => undefined) })
  await page.evaluate(() => history.back())
  await expect.poll(() => cancelConfirmations).toBe(1)
  await expect(page).toHaveURL(/storyboard=storyboard-b/)
  await expect(page.getByRole('heading', { name: '工作 B' })).toBeVisible()
  await expect(page.getByLabel('画面描述')).toHaveValue('默认入口返回前的 B 编辑')
  await expect(page.getByText('未保存', { exact: true })).toBeVisible()

  page.once('dialog', (dialog) => { void dialog.accept().catch(() => undefined) })
  await page.evaluate(() => history.back())
  await expect(page).toHaveURL(/\/projects\/project-1\/scripts\/script-1\/storyboard$/)
  await expect(page.getByRole('heading', { name: '工作 A' })).toBeVisible()
})

test('response injected F2: completed result detail cannot replace an edit made while it loads', async ({ page }) => {
  const mock = await installBoards(page, { completedRun: true }); await open(page, '&run=run-completed'); await mock.waitForB
  await page.getByLabel('画面描述').fill('等待生成 B 时编辑的 A')
  mock.releaseB()
  await expect(page.getByText('新分镜已保存。当前编辑在等待期间发生变化，未自动切换。')).toBeVisible()
  await expect(page).toHaveURL(/storyboard=storyboard-a/); await expect(page.getByRole('heading', { name: '工作 A' })).toBeVisible(); await expect(page.getByLabel('画面描述')).toHaveValue('等待生成 B 时编辑的 A'); await expect(page.getByText('未保存', { exact: true })).toBeVisible()
})

for (const mode of ['success', 'conflict'] as const) {
  test(`response injected F3: delayed ${mode} save for A cannot overwrite active B`, async ({ page }) => {
    const mock = await installBoards(page, { delaySave: mode }); await open(page)
    await page.getByLabel('画面描述').fill(`A 延迟 ${mode}`); await page.getByRole('button', { name: '保存修改' }).click(); await mock.waitForSave
    page.once('dialog', (dialog) => dialog.accept()); await changeToB(page)
    mock.releaseSave()
    await expect(page).toHaveURL(/storyboard=storyboard-b/); await expect(page.getByRole('heading', { name: '工作 B' })).toBeVisible(); await expect(page.getByLabel('画面描述')).toHaveValue('B 原内容'); await expect(page.getByText('已保存', { exact: true })).toBeVisible()
  })
}

for (const [code, message] of [
  ['budget_exhausted', '本活动剩余额度不足以继续生成。'],
  ['business_run_limit', '本活动的生成次数已达到上限（含单镜重做）。'],
  ['business_run_duplicate', '该任务已提交，不能重复执行。'],
  ['budget_context_limit', '当前场景或创作要求超过本活动允许的输入长度。'],
  ['budget_uncertain', '费用记录暂时无法确认，已暂停新生成。'],
  ['storyboard_source_quote_invalid', '生成结果中的原文引用与剧本不一致，未通过保存校验。'],
  ['storyboard_result_invalid', '生成结果的格式或镜头字段未通过校验。'],
  ['storyboard_save_failed', '暂时无法确认分镜是否已保存。'],
  ['invalid_structured_output', '生成服务返回的结果格式无效，任务未完成。'],
  ['gateway_failed', '生成服务未能完成任务。']
]) {
  test(`response injected: ${code} explains the next action and preserves saved shots`, async ({ page }) => {
    await installBoards(page)
    await page.route('**/api/v1/screenplay/agent/runs/run-budget', (route) => json(route, { id: 'run-budget', status: 'FAILED', errorCode: code, resultRef: null }))
    await open(page, '&run=run-budget')
    await expect(page.locator('.run-strip')).toContainText(message)
    await expect(page.locator('.run-strip')).not.toContainText(code)
    await expect(page.getByLabel('画面描述')).toHaveValue('A 原内容')
    await expect(page.getByRole('button', { name: '查询原任务' })).toBeVisible()
  })
}

test('response injected: an unsegmented scene has a friendly title without changing its source', async ({ page }) => {
  const { boards } = await installBoards(page)
  boards.a.sourceSnapshot.heading = 'UNSEGMENTED SCRIPT'
  await page.route('**/api/v1/screenplay/scripts/script-1/scenes', (route) => json(route, [{ ...scene, heading: 'UNSEGMENTED SCRIPT' }]))
  await open(page)
  await expect(page.getByRole('heading', { name: '未识别场景标题', exact: true })).toBeVisible()
  await expect(page.locator('.scene-list')).toContainText('未识别场景标题')
  await page.getByRole('button', { name: '生成另一份' }).click()
  await expect(page.getByText('全文暂按一个场景处理，可继续生成。')).toBeVisible()
  await expect(page.getByRole('button', { name: '生成分镜', exact: true })).toBeEnabled()
  expect(boards.a.sourceSnapshot.heading).toBe('UNSEGMENTED SCRIPT')
  expect(boards.a.sourceSnapshot.sceneText).toBe('原文。')
})

for (const status of ['ACCEPTED', 'REJECTED']) {
  test(`response injected: refreshing a ${status} redo run does not reopen its candidate`, async ({ page }) => {
    const { boards } = await installBoards(page)
    let releaseRun: (() => void) | undefined
    const ready = new Promise<void>((resolve) => { releaseRun = resolve })
    await page.route('**/api/v1/screenplay/agent/runs/run-resolved', async (route) => {
      await ready
      await json(route, { id: 'run-resolved', status: 'COMPLETED', errorCode: null, resultRef: { type: 'shot_proposal', id: 'proposal-resolved', storyboardId: boards.a.id } })
    })
    await page.route('**/api/v1/screenplay/storyboards/storyboard-a/shot-proposals/proposal-resolved', (route) => json(route, {
      id: 'proposal-resolved', storyboardId: boards.a.id, targetShotId: boards.a.shots[0].id, sourceRunId: 'run-resolved', baseStoryboardRevision: 1, status,
      candidateShot: { ...boards.a.shots[0], visualDescription: '已处理候选，不应再次显示' }, createdAt: '2026-10-08T00:00:00Z', resolvedAt: '2026-10-08T00:01:00Z'
    }))
    await open(page, '&run=run-resolved')
    releaseRun?.()
    await expect(page.getByText(status === 'ACCEPTED' ? '该候选已采纳。' : '该候选已放弃；原镜头未修改。')).toBeVisible()
    await expect(page.getByRole('region', { name: '原镜头与候选镜头对比' })).toHaveCount(0)
    await expect(page.getByLabel('画面描述')).toHaveValue('A 原内容')
  })
}

test('response injected: blocked clipboard selects the prompt and explains manual copying', async ({ page }) => {
  await installBoards(page)
  await page.addInitScript(() => { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('Permission denied') } } }) })
  await open(page)
  const prompt = page.locator('.field--wide').filter({ has: page.locator('.prompt-label', { hasText: '图像 Prompt' }) })
  await prompt.getByRole('button', { name: '复制', exact: true }).click()
  await expect(prompt.getByRole('status')).toContainText('浏览器未允许复制。已选中文本')
  await expect(prompt.locator('textarea')).toBeFocused()
  expect(await prompt.locator('textarea').evaluate((element: HTMLTextAreaElement) => element.value.slice(element.selectionStart, element.selectionEnd))).toBe('image prompt')
})
