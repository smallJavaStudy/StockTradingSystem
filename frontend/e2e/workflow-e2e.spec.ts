/**
 * 工作流管理 - 端到端测试
 * 覆盖：菜单导航切换 → 工作流列表渲染 → 智能体管理 Tab →
 *      工作流编辑器（节点列表 + JSON 预览）→ 发布/执行入口 → 执行视图加载
 *
 * 约定：
 * - 依赖种子数据：工作流「开发工作流」(PUBLISHED)、「股票分析工作流」(DRAFT)，智能体 9 个
 * - 不删除/不发布种子工作流，不触发真实 LLM 执行（执行视图通过历史实例进入）
 */
import { test, expect } from '@playwright/test'

const DEV_WORKFLOW = '开发工作流'
const STOCK_WORKFLOW = '股票分析工作流'

test.describe('工作流管理 端到端测试', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/workflow')
    await page.waitForSelector('h1', { timeout: 10000 })
  })

  test('TC-01: 菜单导航 - 股票分析与工作流管理互相切换', async ({ page }) => {
    // 当前在工作流管理页
    await expect(page.locator('h1')).toHaveText('工作流管理')

    // 顶部导航两个入口均存在
    const navAnalysis = page.locator('.top-nav .nav-menu a', { hasText: '股票分析' })
    const navWorkflow = page.locator('.top-nav .nav-menu a', { hasText: '工作流管理' })
    await expect(navAnalysis).toBeVisible()
    await expect(navWorkflow).toBeVisible()
    // 工作流菜单处于激活态
    await expect(navWorkflow).toHaveClass(/nav-active/)

    // 切到股票分析
    await navAnalysis.click()
    await expect(page).toHaveURL(/\/$/)
    await expect(page.locator('h1')).toHaveText('智能股票分析')
    await expect(navAnalysis).toHaveClass(/nav-active/)

    // 切回工作流管理
    await navWorkflow.click()
    await expect(page).toHaveURL(/\/workflow$/)
    await expect(page.locator('h1')).toHaveText('工作流管理')
  })

  test('TC-02: 工作流列表 - 种子工作流渲染与状态徽章', async ({ page }) => {
    // 工具栏按钮
    await expect(page.locator('button', { hasText: 'AI 生成工作流' })).toBeVisible()
    await expect(page.locator('button', { hasText: '新建空白工作流' })).toBeVisible()

    // 列表加载完成后应出现表格（种子数据非空）
    const table = page.locator('.workflow-view table')
    await expect(table).toBeVisible({ timeout: 10000 })

    // 种子工作流「开发工作流」：已发布 + 研发流程分类
    const devRow = page.locator('tbody tr', { hasText: DEV_WORKFLOW })
    await expect(devRow.first()).toBeVisible()
    await expect(devRow.first().locator('.badge')).toHaveText('已发布')
    await expect(devRow.first().locator('.tag')).toHaveText('研发流程')

    // 种子工作流「股票分析工作流」：草稿 + 股票分析分类
    const stockRow = page.locator('tbody tr', { hasText: STOCK_WORKFLOW })
    await expect(stockRow.first()).toBeVisible()
    await expect(stockRow.first().locator('.badge')).toHaveText('草稿')
    await expect(stockRow.first().locator('.tag')).toHaveText('股票分析')
  })

  test('TC-03: 智能体 Tab - 列表渲染与新建入口', async ({ page }) => {
    // 切到智能体 Tab
    const agentTab = page.locator('.wf-tab', { hasText: '智能体' })
    await agentTab.click()
    await expect(agentTab).toHaveClass(/active/)

    // 新建入口
    await expect(page.locator('button', { hasText: '新建智能体' })).toBeVisible()

    // 智能体表格渲染，种子共 9 个
    const rows = page.locator('.workflow-view table tbody tr')
    await expect(rows.first()).toBeVisible({ timeout: 10000 })
    const count = await rows.count()
    expect(count, '种子智能体应不少于 9 个').toBeGreaterThanOrEqual(9)

    // 抽查两个已知种子智能体
    await expect(page.locator('tbody tr', { hasText: '需求分析师' }).first()).toBeVisible()
    await expect(page.locator('tbody tr', { hasText: '股票技术面分析师' }).first()).toBeVisible()

    // 表头字段完整
    for (const th of ['名称', '类型', '模型', '温度']) {
      await expect(page.locator('thead th', { hasText: th }).first()).toBeVisible()
    }
  })

  test('TC-04: 工作流编辑器 - 节点列表与 JSON 预览渲染', async ({ page }) => {
    // 从列表进入「开发工作流」编辑器
    const devRow = page.locator('tbody tr', { hasText: DEV_WORKFLOW }).first()
    await expect(devRow).toBeVisible({ timeout: 10000 })
    await devRow.locator('.wf-actions a', { hasText: '编辑' }).click()

    await expect(page).toHaveURL(/\/workflow\/\d+\/edit$/)

    // 名称输入框回填工作流名称
    await expect(page.locator('input.editor-name')).toHaveValue(DEV_WORKFLOW, { timeout: 10000 })

    // 节点编排区域：标题 + 至少一个节点卡片
    await expect(page.locator('.editor-nodes h2')).toContainText('节点编排')
    const nodeCards = page.locator('.node-card')
    await expect(nodeCards.first()).toBeVisible()
    const nodeCount = await nodeCards.count()
    expect(nodeCount, '开发工作流应至少包含 1 个节点').toBeGreaterThan(0)
    // 节点卡片包含 id 徽章与智能体下拉框
    await expect(nodeCards.first().locator('.node-id-badge')).toBeVisible()
    await expect(nodeCards.first().locator('select')).toBeVisible()

    // JSON 预览：包含 nodes 数组且节点数与左侧一致
    await expect(page.locator('.editor-json h2')).toHaveText('definitionJson')
    const jsonText = await page.locator('.json-preview').textContent()
    expect(jsonText).toBeTruthy()
    const def = JSON.parse(jsonText || '{}')
    expect(Array.isArray(def.nodes), 'JSON 预览应包含 nodes 数组').toBe(true)
    expect(def.nodes.length).toBe(nodeCount)

    // 手动编辑 JSON 入口存在
    await expect(page.locator('button', { hasText: '手动编辑 JSON' })).toBeVisible()
  })

  test('TC-05: 发布/执行入口 - 列表与编辑器按钮状态', async ({ page }) => {
    // 列表页：PUBLISHED 的开发工作流有「执行」入口，DRAFT 的股票分析工作流有「发布」入口
    const devRow = page.locator('tbody tr', { hasText: DEV_WORKFLOW }).first()
    const stockRow = page.locator('tbody tr', { hasText: STOCK_WORKFLOW }).first()
    await expect(devRow).toBeVisible({ timeout: 10000 })
    await expect(devRow.locator('.wf-actions a', { hasText: '执行' })).toBeVisible()
    await expect(stockRow.locator('.wf-actions a', { hasText: '发布' })).toBeVisible()

    // 点击「执行」弹出执行弹窗（不真正启动，验证入口可用后取消）
    await devRow.locator('.wf-actions a', { hasText: '执行' }).click()
    const modal = page.locator('.modal')
    await expect(modal).toBeVisible()
    await expect(modal.locator('h3')).toContainText(`执行工作流：${DEV_WORKFLOW}`)
    await expect(modal.locator('button', { hasText: '执行' })).toBeEnabled()
    await modal.locator('button', { hasText: '取消' }).click()
    await expect(modal).toBeHidden()

    // 编辑器页：PUBLISHED 工作流的「发布」「执行」按钮均可用
    await devRow.locator('.wf-actions a', { hasText: '编辑' }).click()
    await expect(page.locator('input.editor-name')).toHaveValue(DEV_WORKFLOW, { timeout: 10000 })
    const actions = page.locator('.editor-actions')
    await expect(actions.locator('button', { hasText: '保存' })).toBeVisible()
    await expect(actions.locator('button', { hasText: '发布' })).toBeEnabled()
    await expect(actions.locator('button', { hasText: '执行' })).toBeEnabled()
  })

  test('TC-06: 执行视图 - 通过历史实例进入并渲染节点时间线', async ({ page }) => {
    // 打开「开发工作流」的执行历史弹窗
    const devRow = page.locator('tbody tr', { hasText: DEV_WORKFLOW }).first()
    await expect(devRow).toBeVisible({ timeout: 10000 })
    await devRow.locator('.wf-actions a', { hasText: '历史' }).click()

    const modal = page.locator('.modal')
    await expect(modal).toBeVisible()
    await expect(modal.locator('h3')).toContainText(`执行历史：${DEV_WORKFLOW}`)

    // 种子环境已有执行记录，点击第一条进入执行视图
    const historyRows = modal.locator('tbody tr.clickable')
    await expect(historyRows.first()).toBeVisible({ timeout: 10000 })
    await historyRows.first().click()

    // 跳转到执行视图
    await expect(page).toHaveURL(/\/workflow\/execution\/[\w-]+$/)
    await expect(page.locator('.execution-title h1')).toHaveText('工作流执行')
    await expect(page.locator('.wf-instance-id')).toContainText('实例:')

    // 放宽断言：出现节点时间线容器（或等待容器），不等待 LLM 全部完成
    const timelineOrWaiting = page.locator('.execution-timeline, .workflow-execution .empty')
    await expect(timelineOrWaiting.first()).toBeVisible({ timeout: 15000 })

    // 历史实例为已结束实例，时间线应渲染出节点卡片
    const timeline = page.locator('.execution-timeline')
    await expect(timeline).toBeVisible({ timeout: 15000 })
    const nodeCards = timeline.locator('.exec-node-card')
    expect(await nodeCards.count(), '执行时间线应至少渲染 1 个节点').toBeGreaterThan(0)
    await expect(nodeCards.first().locator('.node-id-badge')).toBeVisible()

    // 返回列表入口可用
    await page.locator('.back-btn').click()
    await expect(page).toHaveURL(/\/workflow$/)
  })
})
