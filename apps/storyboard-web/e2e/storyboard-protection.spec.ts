import { expect, test, type Page, type Route } from '@playwright/test'

type MockOptions = { proposal?: boolean; runStatus?: string; refreshOnExport?: boolean; delaySave?: boolean; saveConflict?: boolean; multipleStoryboards?: boolean; delayCompletedDetail?: boolean; delayAccept?: boolean; acceptSuccess?: boolean }

const shot = (id: string, visualDescription: string, orderIndex: number) => ({
  id, orderIndex, shotSize: 'MEDIUM', cameraMovement: 'STATIC', visualDescription, dialogue: '', sound: '雨声', durationSeconds: 4,
  imagePrompt: `image ${id}`, videoPrompt: `video ${id}`, sourceQuote: '许晴看着桌上的信。'
})

function storyboard(withProposal = false, id = 'storyboard-1') {
  const shotId = id === 'storyboard-1' ? 'shot' : id
  return {
    id, projectId: 'project-1', createdBy: 'user-1', sourceRunId: 'run-1', revision: 1,
    createdAt: '2026-10-06T00:00:00Z', updatedAt: '2026-10-06T00:00:00Z',
    sourceSnapshot: { scriptId: 'script-1', scriptRevision: 1, sourceSceneId: 'scene-1', sceneNo: id === 'storyboard-1' ? 1 : 2, heading: id === 'storyboard-1' ? 'INT. 分镜 A - 夜' : 'INT. 分镜 B - 夜', sceneText: '雨声中，许晴看着桌上的信。', sceneHash: 'hash' },
    shots: [shot(`${shotId}-1`, id === 'storyboard-1' ? 'A 原镜头：许晴看向信封。' : 'B 原镜头：林舟站在窗边。', 0), shot(`${shotId}-2`, '原镜头：林舟站在窗边。', 1)],
    proposalSummaries: withProposal ? [{ id: 'proposal-1', targetShotId: 'shot-1', baseStoryboardRevision: 1, status: 'PENDING', createdAt: '2026-10-06T00:00:00Z' }] : []
  }
}

const proposal = {
  id: 'proposal-1', storyboardId: 'storyboard-1', targetShotId: 'shot-1', sourceRunId: 'run-proposal', baseStoryboardRevision: 1, status: 'PENDING', createdAt: '2026-10-06T00:00:00Z', resolvedAt: null,
  candidateShot: { shotSize: 'CLOSE_UP', cameraMovement: 'STATIC', visualDescription: '候选：信封停在前景。', dialogue: '', sound: '雨声', durationSeconds: 4, imagePrompt: 'candidate image', videoPrompt: 'candidate video', sourceQuote: '许晴看着桌上的信。' }
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function installWorkspaceMock(page: Page, options: MockOptions = {}) {
  const server = { storyboard: storyboard(Boolean(options.proposal)), storyboards: { 'storyboard-1': storyboard(Boolean(options.proposal)), 'storyboard-2': storyboard(false, 'storyboard-2') } as Record<string, ReturnType<typeof storyboard>>, accepts: 0, saves: 0, refreshes: 0, exportAuthorizations: [] as string[], loads: 0, runReads: 0, generated: 0 }
  let releaseSave: (() => void) | undefined
  let saveStarted: (() => void) | undefined
  let releaseCompletedDetail: (() => void) | undefined
  let completedDetailStarted: (() => void) | undefined
  let releaseAccept: (() => void) | undefined
  let acceptStarted: (() => void) | undefined
  const saveGate = new Promise<void>((resolve) => { releaseSave = resolve })
  const saveSeen = new Promise<void>((resolve) => { saveStarted = resolve })
  const completedDetailGate = new Promise<void>((resolve) => { releaseCompletedDetail = resolve })
  const completedDetailSeen = new Promise<void>((resolve) => { completedDetailStarted = resolve })
  const acceptGate = new Promise<void>((resolve) => { releaseAccept = resolve })
  const acceptSeen = new Promise<void>((resolve) => { acceptStarted = resolve })
  await page.addInitScript(() => sessionStorage.setItem('storyboard-web.session', JSON.stringify({ accessToken: 'old-access', refreshToken: 'old-refresh' })))
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname; const auth = request.headers().authorization ?? ''
    if (path.endsWith('/auth/refresh')) { server.refreshes += 1; await json(route, { accessToken: 'fresh-access', refreshToken: 'fresh-refresh' }); return }
    if (path.endsWith('/screenplay/projects')) { await json(route, []); return }
    if (path.endsWith('/screenplay/projects/project-1/scripts')) { await json(route, []); return }
    if (path.endsWith('/screenplay/scripts/script-1/scenes')) { server.loads += 1; await json(route, [{ id: 'scene-1', scriptVersionId: 'script-1', sceneNo: 1, heading: 'INT. 测试场景 - 夜', rawText: '雨声中，许晴看着桌上的信。', summary: '测试', sortOrder: 0 }]); return }
    if (path.endsWith('/screenplay/projects/project-1/storyboards')) { server.loads += 1; const all=options.multipleStoryboards ? Object.values(server.storyboards) : [server.storyboard]; await json(route, { items: all.map((current) => ({ id: current.id, projectId: current.projectId, scriptId: 'script-1', scriptRevision: 1, sceneNo: current.sourceSnapshot.sceneNo, heading: current.sourceSnapshot.heading, revision: current.revision, shotCount: current.shots.length, createdAt: current.createdAt, updatedAt: current.updatedAt })) }); return }
    if (path.endsWith('/screenplay/storyboards/storyboard-1/shot-proposals/proposal-1')) { await json(route, proposal); return }
    if (path.endsWith('/screenplay/storyboards/storyboard-1/shot-proposals/proposal-1/accept')) {
      server.accepts += 1; acceptStarted?.()
      if (options.delayAccept) await acceptGate
      if (options.acceptSuccess) {
        const current = server.storyboards['storyboard-1']; const saved = { ...current, revision: current.revision + 1, proposalSummaries: [] }
        server.storyboards['storyboard-1'] = saved; server.storyboard = saved; await json(route, saved); return
      }
      await json(route, { code: 'STORYBOARD_REVISION_CONFLICT', message: 'revision conflict' }, 409); return
    }
    if (path.endsWith('/screenplay/storyboards/storyboard-1/export')) {
      server.exportAuthorizations.push(auth)
      if (options.refreshOnExport && auth === 'Bearer old-access') { await json(route, { code: 'UNAUTHORIZED', message: 'expired' }, 401); return }
      await route.fulfill({ status: 200, contentType: 'text/markdown', body: '# 分镜表' }); return
    }
    const storyboardMatch = /\/screenplay\/storyboards\/(storyboard-[12])$/.exec(path)
    if (storyboardMatch && request.method() === 'PUT') {
      const storyboardId=storyboardMatch[1]
      server.saves += 1; saveStarted?.(); const input = request.postDataJSON() as { shots: typeof server.storyboard.shots }
      if (options.delaySave) await saveGate
      if (options.saveConflict) { await json(route, { code: 'STORYBOARD_REVISION_CONFLICT', message: 'revision conflict' }, 409); return }
      const current=server.storyboards[storyboardId]; const saved = { ...current, revision: current.revision + 1, updatedAt: '2026-10-06T00:01:00Z', shots: input.shots.map((item, orderIndex) => ({ ...item, orderIndex })) }; server.storyboards[storyboardId]=saved; if(storyboardId==='storyboard-1') server.storyboard=saved
      await json(route, saved); return
    }
    if (storyboardMatch) { const storyboardId=storyboardMatch[1]; server.loads += 1; if (options.delayCompletedDetail && storyboardId === 'storyboard-2') { completedDetailStarted?.(); await completedDetailGate }; await json(route, server.storyboards[storyboardId]); return }
    if (path.endsWith('/screenplay/agent/runs/run-interrupted')) { server.runReads += 1; await json(route, { id: 'run-interrupted', status: options.runStatus ?? 'INTERRUPTED', errorCode: 'RECOVERY_INTERRUPTED', resultRef: null }); return }
    if (path.endsWith('/screenplay/agent/runs/run-completed')) { server.runReads += 1; await json(route, { id: 'run-completed', status: 'COMPLETED', errorCode: null, resultRef: { type: 'storyboard', id: 'storyboard-2', storyboardId: 'storyboard-2' } }); return }
    if (path.endsWith('/screenplay/projects/project-1/storyboards/generations')) { server.generated += 1; await json(route, { runId: 'run-new', status: 'QUEUED' }); return }
    if (path.endsWith('/screenplay/agent/runs/run-new')) { await json(route, { id: 'run-new', status: 'INTERRUPTED', errorCode: 'RECOVERY_INTERRUPTED', resultRef: null }); return }
    await json(route, { message: `Unhandled mock route ${request.method()} ${path}` }, 500)
  })
  return { server, releaseSave: () => releaseSave?.(), saveSeen, releaseCompletedDetail: () => releaseCompletedDetail?.(), completedDetailSeen, releaseAccept: () => releaseAccept?.(), acceptSeen }
}

async function openWorkspace(page: Page, suffix = '') {
  await page.goto(`/projects/project-1/scripts/script-1/storyboard?storyboard=storyboard-1${suffix}`)
  await expect(page.getByLabel('画面描述')).toBeVisible()
}

test('response injected: dirty proposal acceptance preserves the other local edit', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { proposal: true })
  await openWorkspace(page)
  await page.getByRole('button', { name: /镜头 2/ }).click()
  await page.getByLabel('画面描述').fill('本地未保存：林舟移开视线。')
  await page.getByRole('button', { name: /镜头 1/ }).click()
  await expect(page.getByRole('button', { name: '采纳候选' })).toBeVisible()
  await page.getByRole('button', { name: '采纳候选' }).click()
  await expect(page.getByText('当前分镜有未保存修改。请先保存或加载服务器版本后，再采纳候选；本地编辑未被放弃。')).toBeVisible()
  await page.getByRole('button', { name: /镜头 2/ }).click()
  await expect(page.getByLabel('画面描述')).toHaveValue('本地未保存：林舟移开视线。')
  expect(mock.server.accepts).toBe(0)
  // Saving this edit advances the server revision. The old candidate stays
  // stale, and the UI must surface the backend conflict rather than rewriting
  // its baseRevision to force acceptance.
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByText('已保存为 r2。')).toBeVisible()
  await page.getByRole('button', { name: /镜头 1/ }).click()
  await page.getByRole('button', { name: '采纳候选' }).click()
  await expect(page.getByText('候选基于旧版本，无法采纳。请重新生成候选或加载服务器版本后比较。')).toBeVisible()
  expect(mock.server.accepts).toBe(1)
})

test('response injected: delayed save response keeps newer typing dirty', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { delaySave: true })
  await openWorkspace(page)
  await page.getByLabel('画面描述').fill('保存快照 A')
  await page.getByRole('button', { name: '保存修改' }).click()
  await mock.saveSeen
  await page.getByLabel('画面描述').fill('后续输入 B')
  mock.releaseSave()
  await expect(page.getByText('已保存先前修改为 r2；后续输入仍未保存。')).toBeVisible()
  await expect(page.getByLabel('画面描述')).toHaveValue('后续输入 B')
  await expect(page.getByRole('button', { name: '保存修改' })).toBeEnabled()
  expect(mock.server.saves).toBe(1)
})

test('response injected: back and forward between saved storyboards protect dirty query-resource changes', async ({ page }) => {
  await installWorkspaceMock(page, { multipleStoryboards: true })
  await openWorkspace(page)
  await page.getByRole('button', { name: /场 2/ }).click()
  await expect(page).toHaveURL(/storyboard=storyboard-2/)
  await expect(page.getByText('INT. 分镜 B - 夜')).toBeVisible()
  await page.getByLabel('画面描述').fill('B 未保存编辑')
  page.once('dialog', (dialog) => dialog.dismiss())
  await page.goBack()
  await expect(page).toHaveURL(/storyboard=storyboard-2/)
  await expect(page.getByLabel('画面描述')).toHaveValue('B 未保存编辑')
  await expect(page.getByText('未保存', { exact: true })).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept())
  await page.goBack()
  await expect(page).toHaveURL(/storyboard=storyboard-1/)
  await expect(page.getByText('INT. 分镜 A - 夜')).toBeVisible()
  await page.getByLabel('画面描述').fill('A 未保存编辑')
  page.once('dialog', (dialog) => dialog.dismiss())
  await page.goForward()
  await expect(page).toHaveURL(/storyboard=storyboard-1/)
  await expect(page.getByLabel('画面描述')).toHaveValue('A 未保存编辑')
  page.once('dialog', (dialog) => dialog.accept())
  await page.goForward()
  await expect(page).toHaveURL(/storyboard=storyboard-2/)
  await expect(page.getByText('INT. 分镜 B - 夜')).toBeVisible()
})

test('response injected: completed generation detail cannot replace an edit made while its GET is pending', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { multipleStoryboards: true, delayCompletedDetail: true })
  await openWorkspace(page, '&run=run-completed')
  await mock.completedDetailSeen
  await page.getByLabel('画面描述').fill('A 在完成详情等待期间的本地编辑')
  mock.releaseCompletedDetail()
  await expect(page.getByLabel('画面描述')).toHaveValue('A 在完成详情等待期间的本地编辑')
  await expect(page).toHaveURL(/storyboard=storyboard-1/)
  await expect(page.getByRole('button', { name: '保存修改' })).toBeEnabled()
  await expect(page.getByText('新分镜已保存。当前编辑在等待期间发生变化，未自动切换。')).toBeVisible()
})

test('response injected: stale save success cannot replace a newly opened storyboard', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { multipleStoryboards: true, delaySave: true })
  await openWorkspace(page)
  await page.getByLabel('画面描述').fill('A 等待保存响应')
  await page.getByRole('button', { name: '保存修改' }).click()
  await mock.saveSeen
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: /场 2/ }).click()
  await expect(page).toHaveURL(/storyboard=storyboard-2/)
  await expect(page.getByText('INT. 分镜 B - 夜')).toBeVisible()
  mock.releaseSave()
  await expect(page.getByText('INT. 分镜 B - 夜')).toBeVisible()
  await expect(page.getByLabel('画面描述')).toHaveValue('B 原镜头：林舟站在窗边。')
  await expect(page.getByText('已保存', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存修改' })).toBeDisabled()
})

test('response injected: stale save conflict cannot attach a conflict notice to the newly opened storyboard', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { multipleStoryboards: true, delaySave: true, saveConflict: true })
  await openWorkspace(page)
  await page.getByLabel('画面描述').fill('A 会冲突的保存')
  await page.getByRole('button', { name: '保存修改' }).click()
  await mock.saveSeen
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: /场 2/ }).click()
  await expect(page).toHaveURL(/storyboard=storyboard-2/)
  mock.releaseSave()
  await expect(page.getByText('INT. 分镜 B - 夜')).toBeVisible()
  await expect(page.getByLabel('画面描述')).toHaveValue('B 原镜头：林舟站在窗边。')
  await expect(page.getByText(/服务器版本已更新。本地修改仍保留/)).toHaveCount(0)
  await expect(page.getByRole('button', { name: '保存修改' })).toBeDisabled()
})

test('response injected: stale proposal acceptance cannot replace a newly opened storyboard', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { proposal: true, multipleStoryboards: true, delayAccept: true, acceptSuccess: true })
  await openWorkspace(page)
  await expect(page.getByRole('button', { name: '采纳候选' })).toBeVisible()
  await page.getByRole('button', { name: '采纳候选' }).click()
  await mock.acceptSeen
  await page.getByRole('button', { name: /场 2/ }).click()
  await expect(page).toHaveURL(/storyboard=storyboard-2/)
  mock.releaseAccept()
  await expect(page.getByText('INT. 分镜 B - 夜')).toBeVisible()
  await expect(page.getByLabel('画面描述')).toHaveValue('B 原镜头：林舟站在窗边。')
  await expect(page.getByText('候选已采纳，只更新了当前镜头。')).toHaveCount(0)
})

test('response injected: cancelling Link and browser-back navigation retains URL and edit', async ({ page }) => {
  const mock = await installWorkspaceMock(page)
  await page.goto('/projects')
  await openWorkspace(page)
  await page.getByLabel('画面描述').fill('离开前的本地编辑')
  const original = page.url()
  // `click()` waits while a native confirm is open, so resolve the dialog from
  // the event callback. The catch covers Chromium's harmless "already
  // handled" race when its navigation cancellation wins first.
  page.once('dialog', (dialog) => { void dialog.dismiss().catch(() => undefined) })
  await page.getByRole('link', { name: '剧本版本' }).click()
  await expect.poll(() => page.url()).toBe(original)
  await expect(page.getByLabel('画面描述')).toHaveValue('离开前的本地编辑')
  page.once('dialog', (dialog) => { void dialog.dismiss().catch(() => undefined) })
  // Trigger a real browser POP without awaiting a navigation event: a blocked
  // POP deliberately has no commit for Playwright to wait on.
  await page.evaluate(() => history.back())
  await expect.poll(() => page.url()).toBe(original)
  await expect(page.getByLabel('画面描述')).toHaveValue('离开前的本地编辑')
  expect(mock.server.loads).toBeGreaterThan(0)
})

test('response injected: INTERRUPTED stops polling and permits a new generation', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { runStatus: 'INTERRUPTED' })
  await openWorkspace(page, '&run=run-interrupted')
  await expect(page.getByText('任务已中断：RECOVERY_INTERRUPTED')).toBeVisible()
  // React StrictMode may perform the initial recovery check twice in dev. The
  // assertion is that no timed polling request follows either initial check.
  const readsAfterRecovery = mock.server.runReads
  expect(readsAfterRecovery).toBeGreaterThan(0)
  await page.waitForTimeout(2_800)
  expect(mock.server.runReads).toBe(readsAfterRecovery)
  await page.getByRole('button', { name: '生成另一份' }).click()
  await expect(page.getByRole('button', { name: '生成分镜' })).toBeEnabled()
  await page.getByRole('button', { name: '生成分镜' }).click()
  await expect.poll(() => mock.server.generated).toBe(1)
})

test('response injected: FINALIZING remains an active, cancellable state', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { runStatus: 'FINALIZING' })
  await openWorkspace(page, '&run=run-interrupted')
  await expect(page.getByText('正在保存结果').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '停止' })).toBeEnabled()
  const readsAfterInitialRefresh = mock.server.runReads
  await page.waitForTimeout(2_800)
  expect(mock.server.runReads).toBeGreaterThan(readsAfterInitialRefresh)
})

test('response injected: refresh retry uses the new token without reloading the dirty workspace', async ({ page }) => {
  const mock = await installWorkspaceMock(page, { refreshOnExport: true })
  await openWorkspace(page)
  await page.getByLabel('画面描述').fill('令牌续期期间的本地编辑')
  const loadsBeforeRefresh = mock.server.loads
  page.once('dialog', (dialog) => dialog.dismiss())
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 Markdown' }).click()
  await download
  await expect(page.getByLabel('画面描述')).toHaveValue('令牌续期期间的本地编辑')
  expect(mock.server.refreshes).toBe(1)
  expect(mock.server.exportAuthorizations).toEqual(['Bearer old-access', 'Bearer fresh-access'])
  expect(mock.server.loads).toBe(loadsBeforeRefresh)
})
