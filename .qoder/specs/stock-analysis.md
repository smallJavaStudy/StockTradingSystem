# 股票分析功能 — 实现计划

## Context

当前系统已有股票数据采集（KimiDataAgent/DeepSeekDataAgent）、数据存储（9张MySQL表）和基础展示（StockList/StockDetail）。但缺少**一键多维度深度分析**能力——用户想要输入一个模糊的股票名称，系统自动解析为精确代码，然后同时从多个角度（K线技术面、走势、板块、资金、舆情等）进行AI驱动的分析，结果汇总到一个Dashboard页面展示。

技术基础已完备：
- 前端：Vue 3 + Vite + Pinia + Axios
- 后端：Spring Boot 3.4.3 + JPA + MySQL（9张表，20个API）
- AgentScope：`agentscope-harness:2.0.0-RC2`，已有 OpenAIChatModel + HarnessAgent 模式
- AgentScope 已能对接 DeepSeek (baseUrl: api.deepseek.com) 和 Kimi (baseUrl: api.kimi.com/coding)

核心架构决策：将 AgentScope 集成到 Spring Boot 后端，实现在 API 层**动态创建 Agent 实例**，每个分析方向一个 Agent，并行执行后汇总结果。

---

## 架构总览

```
用户输入股票名称 → POST /resolve (Kimi Agent 解析 → 候选列表)
    → 用户选择标的 + 勾选分析方向 + 选模型
    → POST /analyze → 后端异步创建N个Agent并行执行 → 返回 sessionId
    → 前端轮询 GET /session/{id} → 结果逐个到达 → Dashboard 卡片逐步亮起
    → K线图直接渲染（无需等Agent），Agent分析卡片异步填充
```

**模型选择**：所有分析 Agent 使用同一模型（用户单选），股票名称解析始终使用 Kimi。

---

## 推荐分析方向（10个）

| # | 方向 | 说明 | Agent 数据来源 |
|---|------|------|---------------|
| 1 | 日K线技术分析 | 蜡烛图形态、均线、量价关系 | DB K线数据 + LLM分析 |
| 2 | 近期走势研判 | 趋势方向、多周期涨跌、动量评估 | DB K线数据 |
| 3 | 所属板块分析 | Kimi从同花顺查板块清单，排名前5 | LLM内部知识（同花顺） |
| 4 | 资金面分析 | 主力资金、北向资金、大单流向 | DB资金数据 + LLM |
| 5 | 市场舆情分析 | 近期新闻情绪、社交媒体热度 | LLM内部知识 |
| 6 | 基本面分析 | 营收增速、利润率、ROE、业务结构 | DB财务数据 |
| 7 | 估值分析 | PE/PB/PS历史分位、行业对比 | DB财务数据 |
| 8 | 筹码结构分析 | 筹码集中度、平均成本、获利比例 | DB筹码数据 + LLM |
| 9 | 风险预警 | 结构性/周期性/事件性风险排序 | LLM综合分析 |
| 10 | 综合投资建议 | 多维度汇总、评级、目标价区间 | 前9项结果汇总 |

**K线图（前端直接渲染，不依赖Agent）** 用 ECharts 蜡烛图 + 成交量柱状图。

---

## 实现步骤

### Phase 1: 后端基础设施（4步）

**Step 1**: 在 `backend/pom.xml` 添加 AgentScope 依赖
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>2.0.0-RC2</version>
</dependency>
```

**Step 2**: 创建 `AgentAnalysisSession` 实体
- `backend/src/main/java/com/stock/entity/AgentAnalysisSession.java`
- 字段：id, sessionId(UUID), stockCode, stockName, modelChoice, status(RUNNING/COMPLETED/PARTIAL_FAILED/FAILED), totalElapsedMs, createdAt

**Step 3**: 创建 `AgentAnalysisResult` 实体
- `backend/src/main/java/com/stock/entity/AgentAnalysisResult.java`
- 字段：id, sessionId(FK), direction, agentName, modelName, contentJson(LONGTEXT), inputTokens, outputTokens, elapsedMs, status(SUCCESS/FAILED), errorMessage, createdAt

**Step 4**: 创建两个 Repository
- `AgentAnalysisSessionRepository.java` — `findBySessionId(String)`
- `AgentAnalysisResultRepository.java` — `findBySessionId(String)`

### Phase 2: Agent 基础设施（4步）

**Step 5**: 创建 Agent 相关类
- `backend/src/main/java/com/stock/agent/ModelChoice.java` — 枚举：DEEPSEEK_V4, DEEPSEEK_V5, KIMI
- `backend/src/main/java/com/stock/agent/StockCandidate.java` — record：code, name, market, industry

**Step 6**: 创建 `AnalysisDirection` 枚举（含10套预设 System Prompt）
- 文件：`backend/src/main/java/com/stock/agent/AnalysisDirection.java`
- 每个枚举值包含：directionKey, displayName, systemPrompt（中文分析框架）
- 股票名称解析的 System Prompt 也放这里（RESOLVER）

**Step 7**: 创建 `AgentFactory`
- 文件：`backend/src/main/java/com/stock/agent/AgentFactory.java`
- `createAnalysisAgent(direction, modelChoice, stockCode) → HarnessAgent`
- 内部方法：`buildModel(ModelChoice) → OpenAIChatModel`
  - DEEPSEEK_V4 → apiKey, modelName="deepseek-chat", baseUrl="https://api.deepseek.com"
  - DEEPSEEK_V5 → 同上（目前API相同，预留区分）
  - KIMI → apiKey, modelName="kimi-for-coding", baseUrl="https://api.kimi.com/coding"
- HarnessAgent 构建：.name(), .sysPrompt(), .model(), 不需要 toolkit/compaction（单轮分析）

**Step 8**: 创建 `AgentModelConfig` 配置类
- 文件：`backend/src/main/java/com/stock/config/AgentModelConfig.java`
- `@ConfigurationProperties("agent.model")` 绑定 application.yml 中的模型配置
- application.yml 添加：
```yaml
agent:
  model:
    deepseek-v4:
      api-key: ${DEEPSEEK_API_KEY:sk-xxx}
      base-url: https://api.deepseek.com
      model-name: deepseek-chat
    deepseek-v5:
      api-key: ${DEEPSEEK_API_KEY:sk-xxx}
      base-url: https://api.deepseek.com
      model-name: deepseek-chat
    kimi:
      api-key: ${KIMI_API_KEY:sk-kimi-xxx}
      base-url: https://api.kimi.com/coding
      model-name: kimi-for-coding
```

### Phase 3: 核心服务（3步）

**Step 9**: 创建 `DataContextBuilder`
- 文件：`backend/src/main/java/com/stock/service/DataContextBuilder.java`
- 从 `AnalysisEngineService.buildDataContext()` 提取出来
- `buildDataContext(stockCode) → String`（JSON 字符串）
- 包含：basic, company, industryChain, products, competitors, finances(last 4), klines(last 60 with stats)
- 修改 `AnalysisEngineService.java` 委托给 DataContextBuilder

**Step 10**: 创建 `AgentOrchestrationService`（核心编排）
- 文件：`backend/src/main/java/com/stock/service/AgentOrchestrationService.java`

核心方法：
- `resolveStockName(query) → List<StockCandidate>`
  1. 用 AgentFactory 创建 Kimi RESOLVER Agent
  2. UserMessage: "请把"{query}"解析为具体的A股代码和名称清单，包括模糊匹配"
  3. agent.call() → 解析JSON → 返回候选列表
  
- `startAnalysis(stockCode, directions[], modelChoice) → sessionId`
  1. 查 StockBasic 获取 stockName
  2. 创建 AgentAnalysisSession(status=RUNNING)，保存
  3. 调用 DataContextBuilder 构建数据上下文
  4. 对每个 direction 提交 `@Async` 任务：
     - 用 AgentFactory 创建 Agent
     - 构造 UserPrompt = 数据上下文 + 方向特定指令
     - agent.call() → 解析 → 创建 AgentAnalysisResult → 保存
     - onComplete: 更新 result 状态
  5. 返回 sessionId

- `getSession(sessionId) → session详情 + results列表`
  1. 查 session
  2. 查所有 results（按方向排序）
  3. 如全部完成 → 更新 session.status = COMPLETED
  4. 如部分失败 → session.status = PARTIAL_FAILED

执行策略：
- Phase 1（8个独立Agent并行）：TECHNICAL_ANALYSIS, TREND_ANALYSIS, SECTOR_ANALYSIS, CAPITAL_FLOW, SENTIMENT_ANALYSIS, FUNDAMENTAL_ANALYSIS, VALUATION_ANALYSIS, CHIP_STRUCTURE, RISK_WARNING
- Phase 2（依赖Phase 1完成后串行）：COMPREHENSIVE_ADVICE（接收前9项摘要作为上下文）

**Step 11**: 创建 `AsyncConfig`
- 文件：`backend/src/main/java/com/stock/config/AsyncConfig.java`
- `@EnableAsync` + `ThreadPoolTaskExecutor` bean（core=5, max=10, prefix="agent-"）

### Phase 4: REST API（1步）

**Step 12**: 创建 `AnalysisAgentController`
- 文件：`backend/src/main/java/com/stock/controller/AnalysisAgentController.java`
- 4个端点：

| Method | Path | 功能 |
|--------|------|------|
| POST | `/api/v1/analysis-agent/resolve` | 股票名称解析 → 返回候选列表 |
| POST | `/api/v1/analysis-agent/analyze` | 启动分析 → 返回 sessionId |
| GET | `/api/v1/analysis-agent/session/{sessionId}` | 轮询结果 |
| GET | `/api/v1/analysis-agent/stock/{code}/history` | 历史分析记录 |

### Phase 5: 前端基础（3步）

**Step 13**: 安装 ECharts — `npm install echarts`（在 frontend/ 目录）

**Step 14**: 扩展 API 层
- 修改 `frontend/src/api/stock.ts`：添加 resolveStockName, startAnalysis, getAnalysisSession, getStockAnalysisHistory

**Step 15**: 创建 Pinia Store + 路由
- 创建 `frontend/src/stores/analysis.ts`：管理步骤状态（step 1→2→3）、候选列表、选定方向、模型选择、sessionId、轮询
- 修改 `frontend/src/router/index.ts`：添加 `/analysis` 路由

### Phase 6: 前端组件（6步）

**Step 16**: 创建 `KlineChart.vue`
- `frontend/src/components/KlineChart.vue`
- Props: klineData (array), loading (boolean)
- ECharts 蜡烛图 + 成交量柱状图，支持缩放拖拽

**Step 17**: 创建 `StockResolver.vue`
- `frontend/src/components/StockResolver.vue`
- 搜索输入框 + 候选股票卡片列表（名称/代码/市场/行业）

**Step 18**: 创建 `DirectionSelector.vue`
- `frontend/src/components/DirectionSelector.vue`
- 10个分析方向 checkboxes（全部默认选中）

**Step 19**: 创建 `ModelSelector.vue`
- `frontend/src/components/ModelSelector.vue`
- 3个 radio buttons：DeepSeek V4 PRO / DeepSeek V5 FLASH / Kimi

**Step 20**: 创建 `AnalysisCard.vue`
- `frontend/src/components/AnalysisCard.vue`
- Props: title, status(loading/loaded/error), content
- 三种状态：骨架屏闪烁 → 分析内容 → 错误提示

**Step 21**: 创建 `AnalysisDashboard.vue`
- `frontend/src/components/AnalysisDashboard.vue`
- 3列网格布局（移动端1列）
- KlineChart 全宽 + 9个 AnalysisCard + 综合建议全宽卡片

### Phase 7: 主页面 + 样式（2步）

**Step 22**: 创建 `AnalysisView.vue`
- `frontend/src/views/AnalysisView.vue`
- 3步流程：搜索 → 选择 → Dashboard
- 第3步轮询逻辑：每2秒调用 getSession，结果逐步显示

**Step 23**: 添加 CSS 样式
- 修改 `frontend/src/style.css`
- 新增：dashboard grid, card 闪烁动画（@keyframes shimmer）, 候选卡片样式, 响应式 @media

---

## 文件清单

### 新建文件（21个）

**后端（12个）**：
1. `backend/src/main/java/com/stock/entity/AgentAnalysisSession.java`
2. `backend/src/main/java/com/stock/entity/AgentAnalysisResult.java`
3. `backend/src/main/java/com/stock/repository/AgentAnalysisSessionRepository.java`
4. `backend/src/main/java/com/stock/repository/AgentAnalysisResultRepository.java`
5. `backend/src/main/java/com/stock/agent/ModelChoice.java`
6. `backend/src/main/java/com/stock/agent/StockCandidate.java`
7. `backend/src/main/java/com/stock/agent/AnalysisDirection.java`
8. `backend/src/main/java/com/stock/agent/AgentFactory.java`
9. `backend/src/main/java/com/stock/config/AgentModelConfig.java`
10. `backend/src/main/java/com/stock/config/AsyncConfig.java`
11. `backend/src/main/java/com/stock/service/DataContextBuilder.java`
12. `backend/src/main/java/com/stock/service/AgentOrchestrationService.java`
13. `backend/src/main/java/com/stock/controller/AnalysisAgentController.java`

**前端（8个）**：
14. `frontend/src/stores/analysis.ts`
15. `frontend/src/views/AnalysisView.vue`
16. `frontend/src/components/KlineChart.vue`
17. `frontend/src/components/StockResolver.vue`
18. `frontend/src/components/DirectionSelector.vue`
19. `frontend/src/components/ModelSelector.vue`
20. `frontend/src/components/AnalysisCard.vue`
21. `frontend/src/components/AnalysisDashboard.vue`

### 修改文件（5个）
22. `backend/pom.xml` — 添加 agentscope-harness 依赖
23. `backend/src/main/resources/application.yml` — 添加 agent.model 配置
24. `backend/src/main/java/com/stock/service/AnalysisEngineService.java` — 委托给 DataContextBuilder
25. `frontend/src/router/index.ts` — 添加 /analysis 路由
26. `frontend/src/style.css` — 添加 Dashboard 样式

---

## 验证方案

1. **编译验证**：`mvn clean compile` — 后端 13 个新文件全部编译通过
2. **前端构建**：`npm run build` — Vue 项目无 TS 错误
3. **端到端测试**：
   - 启动 MySQL（确保 stock_db 存在，stock_basic 表有数据）
   - 启动后端 `mvn spring-boot:run`
   - 启动前端 `npm run dev`
   - 访问 `/analysis` → 输入"扬杰科技" → 应返回候选列表含 300373
   - 选择"扬杰科技 300373" → 勾选所有方向 → 选择 DeepSeek V4 → 点击"开始分析"
   - 观察 Dashboard：K线图立即渲染，Agent 卡片逐个完成
   - 验证全部 10 个分析方向均有结果
   - 验证 session 数据已持久化到 agent_analysis_session / agent_analysis_result 表
4. **边缘情况**：
   - 输入不存在的股票名 → 返回空候选列表
   - 数据库无 K线数据 → Agent 仍可分析（K线图为空）
   - API Key 无效 → Agent 返回 FAILED 状态，不阻塞其他 Agent
