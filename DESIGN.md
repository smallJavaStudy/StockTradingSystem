# 工作流管理模块设计文档（DESIGN.md）

> 模块定位：在既有股票分析系统之上，新增一套基于 **Flowable 7.2.0** 的多智能体工作流管理能力——用户（或 AI）用**简化 JSON** 定义 DAG 工作流，发布为 Flowable 流程，执行时每个节点由 PROMPT 型或 AGENTSCOPE 型智能体完成，并通过 SSE 向前端实时推流。
> 代码位置：后端 `backend/src/main/java/com/stock/workflow/`，前端 `frontend/src/{types,api,stores,views}` 中的 workflow 相关文件。目录与表结构详见 `CODE_STRUCTURE.md`。

## 1. 总体架构

```mermaid
flowchart TB
    subgraph 定义态
        U[用户/前端编辑器] -->|definitionJson| WD[(workflow_def)]
        LLM[WorkflowGeneratorService\nLLM 生成/编辑简化 JSON] -->|三重校验后保存 DRAFT| WD
        WD -->|POST /publish| DS[WorkflowDeployService]
        DS --> V1[JsonToBpmnConverter.validate\n环/引用/agentId/孤立节点]
        V1 --> CV[JsonToBpmnConverter.convert\n自动插并行网关]
        CV --> BM[BpmnModel]
        BM --> PV[Flowable ProcessValidator\n结构校验]
        PV -->|deploy wf_{id}.bpmn20.xml| FR[(ACT_RE_* 部署/流程定义)]
        FR -->|回写 processDefinitionKey/deploymentId\nstatus=PUBLISHED, version+1| WD
    end

    subgraph 执行态
        EX[POST /{id}/execute] --> RS[WorkflowRunService.start\n入参写流程变量 goal/stockCode 等]
        DB[(MySQL 真实数据)] -.->|STOCK_ANALYSIS 传 stockCode 时\nStockContextPreloader 预注入 5 个 _xxx_context\nMARKET_REVIEW 预注入 _sentiment_context| RS
        RS --> FE[Flowable 引擎\nasync ServiceTask → Job 队列\nACT_RU_* / ACT_HI_*]
        FE -->|每个 ServiceTask\n${agentTaskDelegate}| DG[AgentTaskDelegate]
        DG -->|FieldExtension 读 nodeId/workflowDefId\n反查 definitionJson 节点配置| WD
        DG -->|promptTemplate 占位符插值\n${goal} / ${_xxx_context} / ${nodeId.output}| DG
        DG -->|type=PROMPT| PR[PromptAgentRunner\n非流式一次调用]
        DG -->|type=AGENTSCOPE| AR[AgentScopeAgentRunner\n流式多轮+工具]
        AR -.->|toolsJson 动态 registerTool| TK[engine.tools 自定义工具×8\nstock_kline/…/data_enrich\nmarket_zt/market_lhb 读真实 DB\nweb_search 走 Serper.dev 真实搜索]
        PR & AR --> DG
        DG -->|输出写流程变量 nodeId_output| FE
        DG -->|REQUIRES_NEW| RL[(workflow_run_log)]
    end

    subgraph 观测态
        DG -->|NODE_STARTED/DELTA/COMPLETED/FAILED| SSE[WorkflowSseService\n历史回放+广播]
        GL[GlobalWorkflowEventListener\nFlowable 引擎事件] -->|PROCESS_COMPLETED/CANCELLED/FAILED| SSE
        SSE -->|event: workflow-event| FEV[前端执行视图\nEventSource + Pinia store]
        QS[GET /{pid} 状态合成视图] -->|RunLog 为主 + ACT_HI_* 兜底| FEV
    end
```

链路总结：**简化 JSON 定义 → JsonToBpmnConverter（校验+转换）→ BpmnModel → Flowable 部署执行（async ServiceTask）→ AgentTaskDelegate 分发 PROMPT / AGENTSCOPE 两类 Runner → RunLog 落库 + SSE 推流 → 前端实时渲染**。

## 2. 简化工作流 JSON Schema

### 2.1 Schema

```json
{
  "name": "工作流名称(字符串)",
  "category": "DEV_PROCESS | STOCK_ANALYSIS | MARKET_REVIEW | CUSTOM",
  "nodes": [
    {
      "id": "节点唯一 id，仅允许字母/数字/下划线/中划线",
      "name": "节点中文名称",
      "agentId": 1,
      "promptTemplate": "节点输入模板，支持 ${goal} 与 ${某节点id.output} 占位符",
      "timeoutSeconds": 600,
      "dependsOn": ["上游节点id"]
    }
  ]
}
```

后端载体类：`WorkflowJsonDefinition`（name/nodes）与 `WorkflowJsonNode`（id/name/agentId/promptTemplate/timeoutSeconds/dependsOn）。`category` 仅存于 `workflow_def.category`，不参与引擎转换；但 **会决定启动时的预注入行为**（`STOCK_ANALYSIS` → 5 个 `_xxx_context`；`MARKET_REVIEW` → `_sentiment_context`，见 §2.6/§4），字段为自由字符串、无枚举校验。

### 2.2 占位符语义（`AgentTaskDelegate.interpolate`）

| 占位符 | 解析规则 |
|---|---|
| `${goal}`（及任意 `${varName}`） | 取**同名流程变量**。启动时 `POST /{id}/execute` 的 `input` map 整体写入流程变量，故 `input.goal` 即 `${goal}` |
| `${_kline_context}` 等 `_` 前缀变量 | 同上取同名流程变量，但值由**后端启动时预注入**（见 §2.6/§4），前端执行弹窗自动跳过、不要求用户填写 |
| `${nodeId.output}` | 取流程变量 `{nodeId}_output`，即上游节点执行完成后由 Delegate 写入的输出全文 |
| 变量缺失 | 替换为空串并记 WARN 日志，不中断执行 |

正则：`\$\{\s*([A-Za-z0-9_\-]+)(\.output)?\s*}`。占位符只在 **Delegate 运行时**插值，与 Flowable 的 JUEL 表达式引擎完全隔离（见 §5.1）。

### 2.3 dependsOn DAG 校验规则（`JsonToBpmnConverter.validate`）

1. 节点 id 非空、唯一、仅 `[A-Za-z0-9_\-]+`（保证 BPMN 元素 id 合法）；
2. `dependsOn` 引用必须存在且不得自依赖；
3. `agentId` 必须存在于 `workflow_agent_def`；
4. **环检测**：Kahn 拓扑排序，存在环时报出涉及节点；
5. **孤立节点检测**：多节点工作流中既无依赖也不被依赖的节点视为断链错误。

### 2.4 并行网关自动插入规则（`JsonToBpmnConverter.convert`）

用户只声明 `dependsOn`，网关全部由转换器推导插入：

| 图特征 | 自动插入 |
|---|---|
| 节点入度 > 1 | 节点前插**汇聚** `ParallelGateway`（id=`join_{nodeId}`） |
| 节点出度 > 1 | 节点后插**分叉** `ParallelGateway`（id=`fork_{nodeId}`） |
| 入度为 0 的根节点 > 1 个 | StartEvent 后插 `fork_start` 再扇出到各根节点 |
| 出度为 0 的叶节点 > 1 个 | 各叶节点先汇入 `join_end` 再连 EndEvent |

每个 JSON 节点转为一个 **异步 ServiceTask**（id=`task_{nodeId}`，`delegateExpression=${agentTaskDelegate}`，`asynchronous=true`），依赖边转为 `exit(前驱) → entry(后继)` 的 SequenceFlow。

### 2.5 示例（并行汇聚）

```json
{
  "name": "股票分析工作流",
  "category": "STOCK_ANALYSIS",
  "nodes": [
    {"id": "fundamental", "name": "基本面分析", "agentId": 4,
     "promptTemplate": "请对以下股票进行基本面分析：${goal}",
     "timeoutSeconds": 600, "dependsOn": []},
    {"id": "technical", "name": "技术面分析", "agentId": 5,
     "promptTemplate": "请对以下股票进行技术面分析：${goal}",
     "timeoutSeconds": 600, "dependsOn": []},
    {"id": "synthesis", "name": "综合研判", "agentId": 6,
     "promptTemplate": "基本面结论：${fundamental.output}\n技术面结论：${technical.output}\n请给出综合研判。",
     "timeoutSeconds": 900, "dependsOn": ["fundamental", "technical"]}
  ]
}
```

转换结果：`start → fork_start → (task_fundamental ∥ task_technical) → join_synthesis → task_synthesis → end`。

### 2.6 流程变量契约（用户输入 / 预注入 / 节点输出）

与前端执行弹窗、种子节点提示词、AI 生成器共享的变量命名契约，**不得更改**：

| 类别 | 变量/占位符 | 来源 | 说明 |
|---|---|---|---|
| 用户输入 | `${stockCode}` | 执行弹窗 `input.stockCode` | STOCK_ANALYSIS 工作流必填（6 位代码），是触发数据预注入的开关；缺失时跳过预注入并记 WARN |
| 用户输入 | `${stockName}` | 执行弹窗 `input.stockName` | 可选；用户未填且 DB 可查到时由 `StockContextPreloader.resolveStockName` 自动回填 |
| 用户输入 | `${goal}` | 执行弹窗 `input.goal` | 通用任务目标（开发/博客/题材/组合等非股票工作流的主输入） |
| 用户输入 | `${requirement}` | 执行弹窗 `input.requirement` | ★ 「功能开发协作工作流」专用入口（唯一必填变量，见 §5.7.2）：待开发的功能需求描述；由三个调研节点与蓝图/开发等需要原始需求的节点直接引用，其余节点通过 `${nodeId.output}` 承接上游 |
| 预注入 | `${_kline_context}` | 后端启动时注入 | 近 120 日K线摘要：最新价、MA5/20/60、量价特征、近 30 日明细 |
| 预注入 | `${_finance_context}` | 同上 | 最近多期（≤8）财务指标：EPS/ROE/营收/净利及同比 |
| 预注入 | `${_fundflow_context}` | 同上 | 近 20 日资金流：主力净流入序列与趋势 |
| 预注入 | `${_industry_context}` | 同上 | 行业/产业链定位/主营/竞品 |
| 预注入 | `${_company_context}` | 同上 | 公司基本信息（名称/市场/行业/法人/上市日期/简介） |
| 预注入 | `${_sentiment_context}` | 同上（★ 本轮新增） | 仅 `category=MARKET_REVIEW` 工作流注入：近 10 交易日市场情绪指标序列（涨停数/连板高度/封板率/两融环比 + 综合得分），常量 `WorkflowRunService.VAR_SENTIMENT`，构建失败仅记 error 不中断启动 |
| 节点输出 | `${nodeId.output}` | 上游节点完成后写入 | 流程变量 `{nodeId}_output`，供下游插值 |
| 系统 | `workflowDefId` | 启动时写入 | Delegate 反查定义与历史实例过滤用，不供提示词引用 |

约定：**下划线开头 = 后端预注入**。前端执行弹窗（WorkflowListView / WorkflowEditorView）扫描 promptTemplate 提取待填变量时，自动跳过 `_` 开头的占位符与 `${nodeId.output}` 引用，只向用户展示用户级变量。预注入变量单类数据缺失时填固定占位文本“（暂无该类数据）”，绝不 Mock 假数据。

## 3. 两类智能体设计

`AgentTaskDelegate` 按 `AgentDef.type` 分发（未知类型直接抛错）：

| 维度 | PROMPT 型（`PromptAgentRunner`） | AGENTSCOPE 型（`AgentScopeAgentRunner`） |
|---|---|---|
| 定位 | 一次性纯 LLM 调用，输入→输出 | 多轮推理 + 工具调用的自治智能体 |
| 调用范式 | 非流式 `OpenAIChatModel`（`stream(false)`）+ `HarnessAgent.call(...).block(timeout)` | 流式 `OpenAIChatModel`（`stream(true)`）+ `HarnessAgent.streamEvents(...)`，`blockLast(timeout)` |
| 工具 | **强制禁用**全部 Harness 内置工具组（disableFilesystemTools/disableShellTool/disableMemoryTools）——否则模型会进入工具探索，最终文本可能只剩规划前言 | `toolsJson`（JSON 字符串数组）支持两类共 **11 项**白名单：① 内置工具组 `filesystem / shell / memory` **反向裁剪**（未列出的调 disableXxx() 关闭）；② 自定义工具 `stock_kline / stock_finance / stock_fundflow / stock_industry / data_enrich` + ★ `market_zt / market_lhb / web_search`（共 8 项，常量 `AgentScopeAgentRunner.CUSTOM_STOCK_TOOLS`）按名**选择性 `Toolkit.registerTool`**（见 §3.1）；未知工具名记 WARN 并忽略 |
| maxIterations | 不适用 | 映射到 `HarnessAgent.Builder#maxIters(int)`（框架原生迭代上限） |
| loopDepth | 不适用 | 2.0-RC2 无对应 API，作为**提示词级约束**追加到 sysPrompt（"嵌套层级不得超过 N 层"） |
| 事件 | 无流式事件，仅最终结果 | 默认订阅 `TEXT_BLOCK_DELTA` 事件，经 onDelta 回调由 Delegate 转发为 SSE `NODE_DELTA`；`eventsJson` 为 **JSON 字符串数组（事件类型名）**：非空时按该订阅集合**过滤** `streamEvents()` 的事件转发，为空/null 时保持默认行为（向后兼容） |
| 模型配置 | 共用 `AgentModelConfig`（application.yml `agent.model.*`），`modelChoice` 支持 deepseek-v4 / deepseek-v5 / kimi（兼容下划线大写写法） | 同左 |
| 超时/空输出 | Reactor block 超时转 `IllegalStateException("…执行超时")`；输出为空抛错 | 同左（`blockLast` 超时同样处理） |

两个 Runner 都严格复用既有股票分析模块中已验证的 API 范式（`AgentFactory` / `DataEnricher.callAgent` / `AgentOrchestrationService`），避免踩 RC2 版本 API 的坑。

### 3.1 AgentScope 自定义工具（`engine.tools` 包）

★ 8 个（由 5 个逐步扩展至 8 个）方法上标 `@Tool` 注解的 Spring Bean（`io.agentscope.core.tool.Tool/ToolParam`，与 agent-scope 模块 WebSearchTool 同一机制，2.0.0-RC2 已验证），由 `AgentScopeAgentRunner` 按 `AgentDef.toolsJson` 选择性 `Toolkit.registerTool` 注册到当次构建的 HarnessAgent（内置工具组仍由 Harness 自动装配 + disableXxx 裁剪，两轨互不干扰）：

| 工具名 | 参数 | 能力 | 底层数据源 |
|---|---|---|---|
| `stock_kline` | stockCode, days(5-250, 默认 120) | 日K线摘要：最新价、MA5/20/60、量价特征、近 30 日明细 | `StockContextPreloader.buildKlineContext`（MySQL 真实行情） |
| `stock_finance` | stockCode | 最近多期 EPS/ROE/营收/净利及同比 | `buildFinanceContext` |
| `stock_fundflow` | stockCode, days(5-60, 默认 20) | 近N日主力净流入序列与趋势 | `buildFundflowContext`（新增 `stock_fund_flow` 表） |
| `stock_industry` | stockCode | 行业/产业链定位、主营与竞品对比 | `buildIndustryContext` |
| `data_enrich` | stockCode, topic | 先走 Serper 网络搜索，再由 LLM 级联提炼补充信息（舆情/估值/北向资金等 DB 没有的内容），耗时 10~60s | 股票分析模块 `DataEnricher`（DeepSeek→Kimi 级联，`enrichment_data` 表 6 小时缓存，缓存 key 前缀 `WF_ENRICH_`） |
| ★ `market_zt` | date（yyyy-MM-dd，缺省最新有数据交易日）, poolType（TODAY 默认 \| PREVIOUS 昨日涨停今日表现） | 涨停股池：涨停家数、最高连板、连板梯队分布、炸板统计（openTimes>0）、行业分布 Top10、个股明细 Top40（含 ztStat 如 "7/7"） | `MarketZtTool` → `StockZtPoolRepository`（`stock_zt_pool` 表） |
| ★ `market_lhb` | date（yyyy-MM-dd，stockCode 为空时生效）, stockCode（6 位，填写后查该股近期上榜记录并忽略 date） | 龙虎榜：当日上榜明细（净买额降序 Top40）或个股近 20 次上榜，含买/卖/净买额（元→万/亿可读）、上榜原因与机构解读 | `MarketLhbTool` → `StockLhbDetailRepository`（`stock_lhb_detail` 表） |
| ★ `web_search` | query（关键词，必填）, type（search 默认 \| news 新闻搜索） | 实时网络搜索：秒级返回真实搜索结果（标题/摘要/来源/日期），适合最新新闻公告、行情动态、行业政策与舆情；**系统默认优先的外部数据通道**（比 data_enrich 快且为真实网络数据） | `WebSearchTool` → `SerperSearchService`（Serper.dev / Google；`serper.api-key` 未配时返回“未配置”提示而不报错） |

设计要点：入参一律按字符串接收宽松解析（`ToolParams`：股票代码 6 位校验、days 非法时用默认值并夹取区间）；工具内部异常不外抛，统一返回 `[工具名] 错误描述` 文本让模型自行处置；查无数据返回“（暂无该类数据）”，禁止 Mock。★ `GET /api/v1/workflow-agent/available-tools` 现返回全部 **11 项**（内置工具组 3 + 自定义工具 8）及各自参数说明；新增工具需同时在 `AgentScopeAgentRunner.CUSTOM_STOCK_TOOLS` 白名单与 `AgentDefController` 工具说明表中登记，否则智能体保存时会被白名单校验拒绝（400）。两个 market_* 工具均为 **只读**（`readOnly = true, concurrencySafe = true`），不受单股 stockCode 约束，服务于市场域（复盘/题材）工作流；日期缺省时均走 `findMaxTradeDate()` 取最新有数据交易日，查无数据时在文本中回报“最新有数据日期”引导模型重试（参见 §5.8 北向/数据断档注意事项）。

## 4. 执行与状态模型

- **★ 数据准备闸门**（`WorkflowRunService.start` → `StockDataAcquisitionService.ensureStockData`，本轮新增）：`category=STOCK_ANALYSIS` 且含 `stockCode` 时，**先盘点本地库再自动补数**，再走预注入。各数据域分源拉取：**实时行情首选 Serper**（`SerperSearchService.fetchQuote`：Google 行情卡 answerBox/organic 摘要正则解析现价与涨跌幅，入库时由现价−涨跌额反推昨收；仅在已知真实名称时补 basic，东财拉到真实名时自愈覆盖占位名），未命中才降级东财全字段行情；**日K线走 `KimiKlineService` 多源级联：腾讯 ifzq 直连为主 → 新浪直连次之 → Kimi agent loop 兜底**（东财 push2his K线接口频繁连接中断，已不再是K线数据源；Kimi 兜底声明受限 `http_get` 工具——域名白名单 gtimg.cn/tencent.com/sina.cn/sina.com.cn/eastmoney.com，由 Kimi 自主选择可用行情 URL、本服务代执行并把结果喂回，最终整理为固定 CSV `date,open,close,high,low,volume`；全程不执行 LLM 生成的代码，避免任意代码执行风险），新浪成交量单位为股、归一为手与腾讯对齐；财务（datacenter RPT_LICO_FN_CPD，含 REPORTDATE/REPORT_DATE 双向兼容）与 60 日资金流仍走东财（接口契约与 `data-fetcher/import_stock.py`/`import_fundflow.py` 一致，含价格 /100 归一），外部调用间隔 `stock-data.acquire.interval-ms`（缺省 5s）限流。**快速失败语义**：拉取后 K线仍 < `MIN_KLINES_FOR_TRADEABLE=10` 条则抛 `IllegalStateException` 阻断启动（→ 409 + 原因明细），避免 11 个 LLM 节点在数据真空上空跑烧 token；财务/资金流拉取失败不阻断（对应节点降级 + data_enrich/web_search 补定性信息）。本地数据完备时零开销直接放行。★ 运维端点 `POST /api/stock/{code}/acquire` 可单独触发闸门补数（不启动工作流不烧 token）。注：K线经腾讯/新浪直连后不再依赖东财；财务/资金流时序目前仍只能靠东财，Serper 首选仅覆盖行情快照一类。
- **真实数据预注入**（`WorkflowRunService.start` → `StockContextPreloader`）：`category=STOCK_ANALYSIS` 且启动输入含 `stockCode` 时，启动前从 MySQL 真实数据拼装 5 个 `_xxx_context` 流程变量（契约见 §2.6）并回填 `stockName`。每类数据独立兜底：单类失败/为空只影响该变量（填“（暂无该类数据）”）；预注入整体失败仅记 error，**绝不中断流程启动**。意义：各分析节点无需工具调用即可拿到真实行情/财务/资金/行业/公司数据，PROMPT 型节点也能基于真实数据分析；预注入（推数据）与 §3.1 自定义工具（拉数据）双轨并存，AGENTSCOPE 节点可用工具按需补查。
- **★ 市场情绪预注入**（`WorkflowRunService.preloadSentimentContext` → `MarketSentimentService`）：`category=MARKET_REVIEW` 的工作流启动时自动注入 `_sentiment_context`（近 `SENTIMENT_CONTEXT_DAYS=10` 交易日情绪指标序列），无需用户传任何参数；构建异常仅记 error，**绝不中断流程启动**（该变量缺失时占位符插值为空串并记 WARN）。
- **节点粒度日志**：Delegate 在执行前写 `workflow_run_log(RUNNING)`，成功后 `COMPLETED`+输出全文，失败 `FAILED`+错误信息（截 2000 字符），全部走 REQUIRES_NEW（见 §5.3）。
- **节点输出传递**：成功输出写流程变量 `{nodeId}_output`（存于 ACT_RU_/ACT_HI_ 变量表），供下游 `${nodeId.output}` 插值。
- **失败语义**：节点异常原样抛出 → Flowable Job 重试（生产默认 3 次）耗尽后进**死信队列**；`GlobalWorkflowEventListener` 捕获 `JOB_EXECUTION_FAILURE` 推 `PROCESS_FAILED`。
- **状态合成视图**（`WorkflowRunService.getStatus`）：
  - 流程级：HistoricProcessInstance 已结束 → `deleteReason` 非空为 CANCELLED，否则按有无 FAILED 日志判 FAILED/COMPLETED；未结束 → 有死信 Job 或 FAILED 日志判 FAILED，否则 RUNNING；
  - 节点级：RunLog 为主（同 nodeId 取最新），`HistoricActivityInstance(serviceTask)` 兜底补齐已进入引擎但尚未写日志的节点；输出超 60000 字符截断。
  - **入库文本净化**（`WorkflowTextUtils.stripNonBmp`）：MySQL utf8 三字节字符集无法存储非 BMP 字符（emoji），LLM 输出含 emoji 时写 `ACT_HI_VARINST` 失败会使异步作业进死信队列、后续节点永不启动。因此流程变量写入（启动输入 + 节点输出）与 RunLog 入库前统一剔除 codePoint > 0xFFFF 字符；SSE 推送不入库、不净化。

## 5. 关键设计决策与理由

### 5.1 FieldExtension 只存 nodeId / workflowDefId

**问题**：`promptTemplate` 含 `${goal}`、`${n1.output}` 占位符；若写入 ServiceTask FieldExtension，Flowable 读取 Field 时会把 `${}` 当 **JUEL 表达式**解析，导致报错或被引擎误替换。
**决策**：FieldExtension 仅写两个纯文本字段 `nodeId`、`workflowDefId`；promptTemplate/agentId/timeoutSeconds 等全部由 `AgentTaskDelegate` 运行时按 nodeId 从 `workflow_def.definition_json` 反查。附带收益：定义修改后无需重部署即可影响未执行节点的配置读取；Delegate 为 Spring 单例，通过 `execution.getCurrentFlowElement()` 读 Field（非字段注入），线程安全。

### 5.2 async ServiceTask + 30 分钟 Job 锁

**决策**：所有 ServiceTask 标记 `asynchronous=true`，由 Flowable AsyncExecutor（★ 本轮扩容为 core 8 / max 16 / queue 100）执行；`async-job-lock-time-in-millis: 1800000`（30 分钟）。
**理由**：① LLM 节点耗时长（节点超时缺省 600s、上限可配 900s+），同步执行会占死 HTTP 线程且并行网关无法真正并发；② Flowable 默认 Job 锁 5 分钟，LLM 调用超过锁时长会被判“执行超时”被其他执行器**重复捞取**，导致节点重复执行——30 分钟锁覆盖最坏情况（含重试）；③ ★ 股票分析工作流 v2 在 data_overview 完成后有约 8 个 LLM 节点同时就绪，原 core 5/max 10 会排队饿死尾部节点，故扩容至 core 8/max 16，并同步把 Hikari 连接池扩到 maximum-pool-size 30 / minimum-idle 10（长 LLM 节点会持有连接，与股票分析模块共享连接池，防耗尽）。

### 5.3 REQUIRES_NEW 写 RunLog

**决策**：`WorkflowRunLogWriter` 所有写操作用 `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` 独立事务。
**理由**：Delegate 运行在 Flowable 的流程事务内，节点抛异常会回滚整个流程事务；若日志与流程共用事务，FAILED 日志会被回滚**吞掉**，前端与排查无从得知失败原因。独立事务保证 RUNNING/COMPLETED/FAILED 三态日志始终落库。

### 5.4 执行态复用 ACT_RU_* / ACT_HI_*，不自建执行实例表

**决策**：不建 `workflow_run` / `workflow_instance` 之类的自有执行态表；流程实例状态、变量、历史全部查 Flowable 的 RuntimeService/HistoryService/ManagementService，仅补一张**节点粒度**的 `workflow_run_log`（存输入/输出全文，这是引擎不提供的业务数据）。
**理由**：引擎已持久化完整执行态与历史（含并发、重试、死信），自建表必然产生**双写一致性**问题；历史实例列表通过 `variableValueEquals("workflowDefId", id)` 直接按流程变量过滤即可。

> ⚠️ **运维警示**：ACT_* 表由 Flowable 引擎自动创建和管理，**严禁手工清理或删除**（包括 TRUNCATE / DELETE / DROP），否则会破坏运行中与历史流程实例数据（Job 队列、流程变量、历史活动），且无法通过应用层恢复。

### 5.5 LLM 生成简化 JSON 而非 BPMN XML + 三重校验回退

**决策**：AI 生成/编辑（`WorkflowGeneratorService`）让 LLM 输出**简化 JSON**（与人工编辑同一 Schema），绝不让 LLM 直接产出 BPMN 2.0 XML。LLM 生成的节点 `agentId` 必须引用系统中**已存在**的智能体（提示词中注入当前智能体清单并禁止编造），**不支持自动补建 AgentDef 草稿**——非法引用会在三重校验阶段被拒绝并触发重试/回退。校验失败带错误明细**重试一次**：generate 场景两次均失败则回退**内置三节点模板**（★ 本轮由单节点升级为“规划→执行→复核”三节点串行，`fallback=true`）；aiEdit 场景两次均失败的回退语义为**保留原 definitionJson 不变**并返回明确错误说明（400），绝不把非法 JSON 落库。
★ **提示词质量标准注入**：生成提示词在 Schema 说明之外额外注入“节点提示词质量标准”（`PROMPT_QUALITY_DOC`），要求每个 promptTemplate 为 15 行以上的五段式结构化提示词：【角色定位】【输入数据（显式列占位符）】【工作步骤（3-6 步思维链）】【输出格式（Markdown 章节）】【质量红线（3-5 条硬约束）】，单行提示词视为不合格；与种子节点提示词（`SeedNodePrompts`）同一标准，保证 AI 生成产物与种子工作流质量对齐。
**理由**：BPMN XML 冗长、命名空间复杂，LLM 生成合法率低且难以定位错误；简化 JSON 面窄、可用确定性代码校验并给出可反馈给 LLM 的结构化错误。三重校验：
1. **剥围栏**：`stripCodeFence` 去 markdown 围栏并截取首 `{` 至末 `}`；
2. **Jackson 反序列化**：结构合法性；
3. **`JsonToBpmnConverter.validate`**：环/引用/agentId 存在性/孤立节点（与发布共用同一套校验，保证"生成即可发布"）。

### 5.6 其他

- **发布再加一道 Flowable `ProcessValidator`** 结构校验，fatal 错误拒绝部署（warning 放行）。
- **SSE 推送绝不影响主流程**：Delegate 内 `pushSafely` 吞掉推送异常；`GlobalWorkflowEventListener.isFailOnException()=false`。
- **种子数据安全**：`WorkflowSeedService` 绝不重复插入、绝不清库、绝不删除/修改用户自建数据（★ 本轮由“按 name 判存幂等”升级为 seedVersion 版本受控原地升级，见 §5.7）。
- **模块隔离**：`WorkflowControllerAdvice` 限定 `basePackages="com.stock.workflow.controller"`，不改变既有股票分析接口的错误行为。

### 5.7 种子升级机制（seedVersion）与种子工作流清单

**问题**：旧版种子逻辑“按 name 判存、存在即跳过”导致种子提示词/工作流结构一经创建就无法随版本演进。
**决策**（`WorkflowSeedService` 重写 + `WorkflowDef/AgentDef` 新增 `seedVersion` 字段）：版本受控的原地 upsert——

1. 不存在 → 创建并记 `seedVersion=目标版本`；
2. 存在且 `seedVersion` 非空且 < 目标版本 → **原地更新**（保留 id 与运行历史）并升 `seedVersion`；
3. 存在但 `seedVersion=null` 且 name 与种子名相同 → 视为种子历史行，首轮升级原地更新并打上 `seedVersion`；
4. 版本已到位 → 跳过；`seedVersion=null` 且名称不同的用户数据永不被触碰（AI 生成的旧博客类工作流仅盘点记日志，不删不改）。

工作流升级后若原状态为 PUBLISHED，自动调 `WorkflowDeployService.deploy` **重新部署**使新定义生效；部署失败仅记 error 不阻断启动（可手动重发）。种子提示词外置为四个常量类：`SeedAgentPrompts`（24 个智能体 systemPrompt：开发域 8 + 股票分析域 11 + 博客域 5）、`SeedNodePrompts`（27 个节点 promptTemplate：股票 11 + 开发 9 + 博客 7）、★ `SeedMarketPrompts`（9 个市场域智能体 systemPrompt + 13 个节点 promptTemplate）与 ★ `SeedDevTeamPrompts`（10 个功能开发协作域智能体 systemPrompt + 14 个节点 promptTemplate），均按五段式质量标准编写。当前种子合计 **43 个 AgentDef + 7 个 WorkflowDef**（原 24 + 3，本轮先增 9 个市场域智能体与 3 个市场域工作流，再增 10 个功能开发协作域智能体与 1 个功能开发协作工作流）；目标版本：开发工作流 v2、股票分析工作流 v2、博客创作工作流 v1，★ 每日复盘/题材挖掘/组合诊断/功能开发协作工作流均 v1；首批 9 个智能体提示词深度化为 v2（`AGENT_V2`），后续新增智能体均为 v1（`AGENT_V1`）。

**股票分析工作流 v2（11 节点：data_overview → 七路并行 + chip/risk → synthesis）**：数据总览后 technical/trend/fundamental/valuation/capital/sector/sentiment 七路并行，chip 依赖 technical、risk 依赖 fundamental+valuation，synthesis 汇聚全部 9 路分析产出 **≥2000 字**综合研判报告；sector/sentiment/synthesis 为 AGENTSCOPE 型带工具，其余为 PROMPT 型：

| 节点 id | 名称 | 智能体（类型/工具） | 依赖 | 超时(s) |
|---|---|---|---|---|
| data_overview | 数据总览 | 数据总览分析师（PROMPT） | — | 600 |
| technical | 技术面分析 | 股票技术面分析师（PROMPT） | data_overview | 600 |
| trend | 趋势研判 | 趋势分析师（PROMPT） | data_overview | 600 |
| fundamental | 基本面分析 | 股票基本面分析师（PROMPT） | data_overview | 600 |
| valuation | 估值分析 | 估值分析师（PROMPT） | data_overview | 600 |
| capital | 资金面分析 | 资金面分析师（PROMPT） | data_overview | 600 |
| sector | 行业与产业链分析 | 行业研究员（AGENTSCOPE：memory + stock_industry + data_enrich） | data_overview | 600 |
| sentiment | 舆情与催化分析 | 舆情分析师（AGENTSCOPE：memory + data_enrich） | data_overview | 600 |
| chip | 筹码结构分析 | 筹码分析师（PROMPT） | technical | 600 |
| risk | 风险预警 | 风险官（PROMPT） | fundamental, valuation | 600 |
| synthesis | 综合研判与投资建议 | 股票综合分析智能体（AGENTSCOPE：memory） | 除 data_overview 外全部 9 节点 | 900 |

**开发工作流 v2（9 节点串行，★ 新增 code_review、launch_verify）**，全部 PROMPT 型：

| 节点 id | 名称 | 智能体 | 依赖 | 超时(s) |
|---|---|---|---|---|
| req_write | 需求编写 | 需求分析师 | — | 600 |
| req_review | 需求评审 | 需求评审员 | req_write | 600 |
| design | 详细设计 | 系统设计师 | req_review | 900 |
| design_review | 详细设计评审 | 设计评审员 | design | 600 |
| develop | 开发 | 开发工程师 | design_review | 900 |
| code_review | 代码评审 | 代码评审员（★ 新增） | develop | 600 |
| test | 测试 | 测试工程师 | code_review | 900 |
| release_check | 上线准备清单 | 测试工程师 | test | 600 |
| launch_verify | 发布验收 | 发布验收员（★ 新增） | release_check | 600 |

**博客文章创作工作流 v1（7 节点，★ 本轮新种子；draft 双依赖 plan+research，其余串行）**：

| 节点 id | 名称 | 智能体（类型/工具） | 依赖 | 超时(s) |
|---|---|---|---|---|
| plan | 选题与大纲 | 内容策划师（PROMPT） | — | 600 |
| research | 素材调研 | 素材调研员（AGENTSCOPE：memory + data_enrich） | plan | 600 |
| draft | 正文创作 | 资深撰稿人（PROMPT） | plan, research | 900 |
| polish | 结构与文笔润色 | 责编（PROMPT） | draft | 600 |
| seo | SEO优化 | SEO专家（PROMPT） | polish | 600 |
| review | 质量终审 | 责编（PROMPT） | seo | 600 |
| publish_pack | 发布包整理 | 责编（PROMPT） | review | 600 |

### 5.7.1 ★ 本轮新增三个市场域种子工作流（T22）

市场域 9 个智能体（均 `AGENT_V1`，提示词在 `SeedMarketPrompts`）：

| 智能体 | 类型 | 工具 | 备注 |
|---|---|---|---|
| 市场数据分析师 | AGENTSCOPE | memory + market_zt + market_lhb | 复盘首节点数据总览 |
| 涨停梯队分析师 | AGENTSCOPE | memory + market_zt | 连板阶梯/炸板率 |
| 龙虎榜资金分析师 | AGENTSCOPE | memory + market_lhb | 席位与净买额 |
| 市场情绪分析师 | PROMPT | — | 依赖预注入 `_sentiment_context` |
| 复盘总结师 | PROMPT | — | ≥1500 字复盘报告 |
| 题材研究员 | PROMPT | — | 题材界定/龙头梳理/报告三节点复用 |
| 题材个股挖掘师 | AGENTSCOPE | memory + market_zt + data_enrich | 从涨停池反推题材成员 |
| 组合数据汇总师 | AGENTSCOPE | memory + stock_kline + stock_finance + stock_fundflow | 逐只调 3 工具，`maxIterations=8` |
| 组合诊断师 | PROMPT | — | 分层排序建议 |

**每日复盘工作流 v1**（`category=MARKET_REVIEW`，5 节点：总览 → 三路并行 → 综合复盘），启动时自动预注入 `_sentiment_context`（见 §2.6/§4），无需用户输入：

| 节点 id | 名称 | 智能体 | 依赖 | 超时(s) |
|---|---|---|---|---|
| review_data | 市场数据总览 | 市场数据分析师 | — | 600 |
| zt_ladder | 涨停梯队分析 | 涨停梯队分析师 | review_data | 600 |
| lhb_capital | 龙虎榜资金分析 | 龙虎榜资金分析师 | review_data | 600 |
| margin_sentiment | 两融与情绪分析 | 市场情绪分析师 | review_data | 600 |
| review_synthesis | 综合复盘 | 复盘总结师 | zt_ladder, lhb_capital, margin_sentiment | 900 |

转换结果：`start → task_review_data → fork_review_data → (zt_ladder ∥ lhb_capital ∥ margin_sentiment) → join_review_synthesis → task_review_synthesis → end`。

**题材挖掘工作流 v1**（`category=CUSTOM`，5 节点；主输入 `${goal}`=题材名称；leader_review 双依赖汇聚）：

| 节点 id | 名称 | 智能体 | 依赖 | 超时(s) |
|---|---|---|---|---|
| topic_define | 题材界定 | 题材研究员 | — | 600 |
| topic_stocks | 关联个股挖掘 | 题材个股挖掘师（market_zt + data_enrich） | topic_define | 900 |
| chain_analysis | 产业链穿透 | 行业研究员（复用股票域智能体） | topic_stocks | 900 |
| leader_review | 龙头梳理 | 题材研究员 | topic_stocks, chain_analysis | 600 |
| topic_report | 综合报告 | 题材研究员 | leader_review | 900 |

**组合诊断工作流 v1**（`category=CUSTOM`，3 节点串行；主输入 `${goal}`=逗号分隔股票代码列表）：

| 节点 id | 名称 | 智能体 | 依赖 | 超时(s) |
|---|---|---|---|---|
| portfolio_data | 组合数据汇总 | 组合数据汇总师（kline+finance+fundflow，maxIters 8） | — | 900 |
| portfolio_compare | 多股对比分析 | 组合诊断师 | portfolio_data | 900 |
| portfolio_advice | 排序建议 | 组合诊断师 | portfolio_compare | 600 |

设计要点：三个新工作流均**不依赖 stockCode 预注入**（复盘靠 `_sentiment_context` + market_* 工具拉数据，题材/组合靠 `${goal}` + 工具），因此可被定时任务（§9.4）与前端“一键复盘”直接触发。

### 5.7.2 ★ 功能开发协作工作流 v1（种子，`category=DEV_PROCESS`）

定位：把本项目真实的软件团队协作链路（三视角调研 → 蓝图 → 分期并行开发 → 测试回归 → 三维评审 → 交付）固化为一条可复用的 14 节点种子工作流，**入口变量 `${requirement}`**（功能需求描述，非 `${goal}`；唯一必填，由需要原始需求的节点直接引用，下游节点靠 `${nodeId.output}` 承接）。

功能开发协作域 10 个新增智能体（均 `AGENT_V1`，提示词在 `SeedDevTeamPrompts`）：

| 智能体 | 类型 | 工具 | 职责 |
|---|---|---|---|
| 产品对标调研员 | PROMPT | — | 对标同类产品拆功能矩阵与差距 |
| 数据源调研员 | AGENTSCOPE | memory | 数据源可得性/字段/更新频率实证 |
| 技术资产盘点师 | AGENTSCOPE | memory | 盘点既有表/接口/组件可复用面 |
| 技术负责人 | PROMPT | — | 融合三路调研产出蓝图与分期路线 |
| 数据管道工程师 | PROMPT | — | 表结构与幂等重跑方案 |
| 前端工程师 | PROMPT | — | 组件树与四态（加载/空/错误/无数据）兜底 |
| 智能体增强工程师 | PROMPT | — | 工作流节点与提示词设计 |
| 深度功能工程师 | PROMPT | — | 实体/CRUD/定时任务 |
| 影响面评审员 | PROMPT | — | 变更影响面与回归风险 |
| 交付负责人 | PROMPT | — | 文档同步与交付总结 |

复用既有开发域种子智能体 3 个：**开发工程师**（后端开发节点）、**测试工程师**（测试与回归方案节点）、**代码评审员**（同时承接完整性评审与正确性评审两个节点）。

14 节点结构（`WorkflowSeedService#buildFeatureDevWorkflowJson`）：

| 节点 id | 名称 | 智能体 | 依赖 | 超时(s) |
|---|---|---|---|---|
| research_product | 产品对标调研 | 产品对标调研员 | — | 600 |
| research_data | 数据源实证调研 | 数据源调研员 | — | 600 |
| research_asset | 技术资产盘点 | 技术资产盘点师 | — | 600 |
| blueprint | 蓝图规划 | 技术负责人 | research_product, research_data, research_asset | 900 |
| dev_pipeline | 一期-数据管道开发 | 数据管道工程师 | blueprint | 900 |
| dev_frontend | 一期-前端开发 | 前端工程师 | blueprint | 900 |
| dev_backend | 一期-后端开发 | 开发工程师（复用） | blueprint | 900 |
| dev_agent | 二期-智能体增强开发 | 智能体增强工程师 | dev_pipeline, dev_backend | 900 |
| dev_deep | 二期-深度功能开发 | 深度功能工程师 | dev_backend, dev_frontend | 900 |
| test_plan | 测试与回归方案 | 测试工程师（复用） | dev_agent, dev_deep | 900 |
| review_complete | 完整性评审 | 代码评审员（复用） | test_plan | 600 |
| review_correct | 正确性评审 | 代码评审员（复用） | test_plan | 600 |
| review_impact | 影响面评审 | 影响面评审员 | test_plan | 600 |
| delivery | 文档同步与交付总结 | 交付负责人 | review_complete, review_correct, review_impact | 900 |

并行网关（按 §2.4 自动推导，无需在 JSON 里声明）：3 个根节点 → `fork_start` 扇出三路调研；`join_blueprint` 汇聚 3 路调研；`fork_blueprint` 扇出一期 3 路；`fork_dev_backend` 扇出至二期两节点，而二期两节点各自双依赖故各预置一个汇聚（`join_dev_agent` / `join_dev_deep`）；`join_test_plan` 汇聚二期 2 路；`fork_test_plan` 扇出 3 维评审；`join_delivery` 汇聚 3 维评审。设计上关注的三大汇聚点为 **blueprint（汇聚 3 路调研）/ test_plan（汇聚二期 2 路）/ delivery（汇聚 3 维评审）**。

设计要点：① 节点超时统一 600–900s（调研与评审 600、规划/开发/测试/交付 900），与既有种子口径一致；② 两个 AGENTSCOPE 型调研节点只开 `memory` 工具，不接股票/市场工具（调研输出为文字结论，不需拉行情）；③ 常量 `FEATURE_DEV_WORKFLOW_NAME` / `FEATURE_DEV_WORKFLOW_VERSION=1`，按 name 走 §5.7 的版本化 upsert，与其余 6 个种子工作流同机制。

### 5.8 已知注意事项

- **MySQL 5.7 的 ACT_* 表 utf8 三字节限制**：Flowable 自建的 ACT_* 表在 MySQL 5.7 下为 utf8（三字节）字符集，无法存储非 BMP 字符（emoji）；应用层已通过 `WorkflowTextUtils.stripNonBmp` 在流程变量与 RunLog 入库前统一净化规避（机制详见 §4），不依赖改库表字符集。
- **★ emoji 净化契约已扩展到全局 LLM 入库路径**：凡是将 LLM 输出写入 MySQL 的新增链路（`StockComparisonService` 对标点评、`MarketSentimentService` 周期定性、`TopicService` 题材缓存）均必须先过 stripNonBmp；新增任何“LLM 结果入库”功能时这是**强制契约**，遗漏会在真实市场文本（常带 📈🔥 等）下直接报错或进死信。
- **★ Hibernate 6.6 禁止对 CLOB/LONGTEXT 字段在 JPQL 中用 SUBSTRING**：`workflow_run_log.output_text` 为 `@Lob`，早期版本的报告预览 JPQL `SUBSTRING(l.outputText,1,200)` 会在**应用启动阶段**就因 SQM 校验失败而报错（不是运行时）；现改为 `ReportRunLogRepository` 中的**原生 SQL** `SUBSTRING(l.output_text, 1, 200)` + 接口投影（`PreviewRow`）。后续新增对 CLOB 字段的截取/聚合查询一律走 nativeQuery。
- **★ 北向资金数据 2024-08-16 后停更**：交易所自 2024 年 8 月起停止每日披露北向资金流向，`stock_north_flow` 表数据止于 **2024-08-16** 且不会再增长。因此：① `MarketSentimentService` 情绪评分**完全不依赖北向**（权重只用涨停数/连板高度/封板率/两融）；② 前端北向面板仅作历史存档展示；③ 提示词不得要求智能体引用“今日北向资金”。
- **★ 市场数据源既定事实**（写提示词与前端展示时必须遵守）：① 涨停池 `reason`（涨停原因）数据源未提供、**恒为 null**，题材线索只能用 `ztStat`（如 "7/7"=7 天 7 板）与 `industry` 推断；② 龙虎榜与大宗交易**同股同日可多行**（多个上榜原因/多笔成交），两表因此**无唯一索引**，抓取脚本靠“先查已存在行再插差集”幂等；③ 深交所两融接口单位为**亿元**（上交所为元），抓取时已 ×1e8 归一为**元**入库。
- **★ 前端路由对未落地视图用 `import.meta.glob` 接线**：Vite 对静态 `import('../views/Xxx.vue')` 的不存在文件会在**编译阶段 500**，导致整个 `router/index.ts` 模块加载失败→**全站白屏**（多人并行开发时极易发生）。因此并行期的视图改用 `import.meta.glob('../views/ReportsView.vue')` 查找，缺失时降级为单路由 `Promise.reject`，不阻断其余路由；文件落地后自动生效。
- **★ 定时任务需 `@EnableScheduling` 才生效**：`config/SchedulingConfig` 是 `DailyScheduleService` 与 `AlertScanService` 两个 `@Scheduled` 的共同前置，删除或改名会使两个定时链路**静默失效**（无报错、日志也无输出），变更时需同步核对（见 §9.4）。
- **历史死信 1 条保留未清理**：开发验证期间因 emoji 写库失败产生的 1 条死信 Job 仍留在 `ACT_RU_DEADLETTER_JOB`（对应历史实例已终态，不影响新流程）；依照 §5.4 运维警示不手工清理 ACT_* 表，故保留待引擎侧处置。

## 6. REST API 契约

统一约定：正常返回实体/Map（HTTP 200）；业务异常由 `WorkflowControllerAdvice` 转为错误契约：

```json
{ "error": true, "status": 400, "message": "错误描述" }
```

| 异常 | 状态码 |
|---|---|
| `IllegalArgumentException`（参数/校验失败） | 400 |
| `NoSuchElementException`（资源不存在） | 404 |
| `IllegalStateException`（状态冲突，如未发布就执行） | 409 |
| 其他异常（只露 message 不露堆栈） | 500 |

### 6.1 WorkflowController — `/api/v1/workflow`

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `/list` | — | `WorkflowDef[]` | 全部工作流，updatedAt 倒序 |
| GET | `/{id}` | — | `WorkflowDef` | 单个定义；不存在 404 |
| POST | `/create` | `{name, description?, category?, definitionJson?}` | `WorkflowDef` | 创建 DRAFT（createdBy=USER）；重名 400 |
| PUT | `/{id}` | 同上（字段可选） | `WorkflowDef` | 更新；原 PUBLISHED 自动置回 DRAFT |
| DELETE | `/{id}` | — | `{deleted: true, id}` | 删除定义 |
| POST | `/{id}/publish` | — | `WorkflowDef` | 校验→转 BPMN→部署，status=PUBLISHED、version+1；校验失败 400 |
| POST | `/generate` | `{description}` | `{workflow: WorkflowDef, fallback: boolean, message}` | AI 生成（提示词含五段式质量标准；失败自动重试一次，仍失败回退内置三节点模板 fallback=true）；节点 agentId 只能引用已存在智能体，**不自动补建 AgentDef 草稿**，非法引用在校验阶段拒绝 |
| POST | `/{id}/ai-edit` | `{instruction}` | `WorkflowDef` | AI 修改 definitionJson；原 PUBLISHED 置回 DRAFT；两次重试均未通过校验则 400 并**保留原 definitionJson 不变**（非法 JSON 绝不落库） |
| POST | `/{id}/execute` | `{input: {goal?, stockCode?, stockName?, ...}}`（可省） | `{processInstanceId}` | 启动流程；未发布 409。STOCK_ANALYSIS 类工作流传 `stockCode` 时后端自动预注入 5 个 `_xxx_context` 真实数据变量并回填 `stockName`（见 §2.6/§4） |

### 6.2 AgentDefController — `/api/v1/workflow-agent`

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `/list` | — | `AgentDef[]` | 全部智能体，updatedAt 倒序 |
| GET | `/{id}` | — | `AgentDef` | 单个智能体；不存在 404 |
| POST | `` （根路径） | `AgentDef`（name 必填） | `AgentDef` | 创建；重名/非法 type/非白名单工具 400 |
| PUT | `/{id}` | `AgentDef`（字段可选） | `AgentDef` | 部分更新 + 同套校验 |
| DELETE | `/{id}` | — | `{deleted: true, id}` | 删除智能体 |
| GET | `/available-tools` | — | `{tools: string[], descriptions: {...}, usage}` | AGENTSCOPE 可用工具清单，★ 现返回 **11 项**：内置工具组 filesystem/shell/memory + 自定义工具 stock_kline/stock_finance/stock_fundflow/stock_industry/data_enrich + ★ market_zt/market_lhb/web_search（含各自参数说明） |

### 6.3 WorkflowExecutionController — `/api/v1/workflow-execution`

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `/{processInstanceId}` | — | `WorkflowStatusView`：`{processInstanceId, status, nodes: [{nodeId, nodeName, status, output, errorMessage, startedAt, completedAt}]}` | 状态合成视图（流程级 + 节点级）；实例不存在 404 |
| GET | `/{processInstanceId}/stream` | — | `text/event-stream` | SSE 事件流（先回放历史再实时推送，见 §7） |
| POST | `/{processInstanceId}/cancel` | `{reason?}` | `{cancelled: true, processInstanceId}` | 取消运行中实例 |
| GET | `/history/{workflowDefId}` | — | `[{processInstanceId, status, startedAt, completedAt}]` | 某定义的历史实例（含运行中），启动时间倒序；status 为轻量判定（RUNNING/CANCELLED/COMPLETED），精确状态以 getStatus 为准 |

### 6.4 ★ ReportLibraryController — `/api/reports`（T21）

报告库：对 `workflow_run_log` 的**只读**聚合检索与导出，不新建表。

| 方法 | 路径 | 参数 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `` （根路径） | `stockCode?`、`workflowName?`、`startDate?`/`endDate?`（ISO yyyy-MM-dd）、`page`=0、`size`=20 | `{content: ReportRunSummary[], page, size, totalElements, totalPages}` | 运行级分页检索（时间倒序），每行含输出前 **200 字符**预览（原生 SQL 投影，见 §5.8）；全部筛选参数可空 |
| GET | `/{runId}` | — | `ReportRunDetail`（汇总 + `ReportNodeDetail[]` 输出全文） | 单次运行节点明细；runId 即 processInstanceId |
| GET | `/{runId}/export` | `format`=md\|html（缺省 md） | `ResponseEntity<byte[]>` + `Content-Disposition: attachment` | 导出报告文件（md 为 `text/markdown;charset=UTF-8`，html 由 **commonmark 0.24.0** 渲染）；非 md/html 格式 400 |

### 6.5 ★ ScheduleController — `/api/schedule`（T22）

| 方法 | 路径 | 响应 | 说明 |
|---|---|---|---|
| POST | `/run-now` | `Map`（含是否已接受/拒绝原因） | 手动触发一轮盘后批处理（`triggerRun("manual")`，异步串行）；已在跑时**拒绝重入** |
| GET | `/status` | `Map`（running 标志 + 最近一次执行摘要） | 供前端轮询展示批处理进展（详见 §9.4） |

### 6.6 ★ MarketDataController — `/api/market`（T19）

均为**只读**接口，数据由 `data-fetcher/fetch_market.py`（AKShare）直写 MySQL（§9.1）；日期参数与返回统一 `yyyy-MM-dd`，字段无值返 null **不做兜底填充**；`date` 缺省时取“最新有数据交易日”，无数据返空数组（非 404）。

| 方法 | 路径 | 参数 | 响应 record | 单位/说明 |
|---|---|---|---|---|
| GET | `/zt-pool` | `date?`、`poolType`=TODAY\|PREVIOUS（缺省 TODAY） | `ZtPoolItem[]` | amount 元，changePct/turnoverRate %，时间 HH:mm:ss；按连板数降序；`reason` 恒 null（§5.8） |
| GET | `/lhb` | `date?` | `LhbItem[]` | 金额元，按净买额降序；同股同日可多行 |
| GET | `/north-flow` | `days`（缺省 30，上限 500） | `NorthFlowItem[]` | netFlow/accumFlow **亿元**，按日期**升序**便于绘图；数据止于 2024-08-16（§5.8） |
| GET | `/margin` | `days`（缺省 30） | `MarginItem[]` | 余额元；每交易日 2 行（market=SH/SZ），日期升序、同日 SH 在前 |
| GET | `/block-trade` | `date?` | `BlockTradeItem[]` | price/amount 元、volume 股、premiumRate %（负为折价），按成交额降序 |
| GET | `/dates` | `type`=zt\|lhb\|block\|margin（缺省 zt） | `LocalDate[]` | 最近 **30** 个有数据交易日（倒序），供前端 `MarketDatePicker` 只列出有数据的日期 |

### 6.7 ★ MarketSentimentController — `/api/market/sentiment`（T22）

| 方法 | 路径 | 参数 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `` （根路径） | `days`（缺省 10，上限 30） | `MarketSentimentService.SentimentSnapshot` | 近 N 交易日 0-100 情绪分与四项指标明细（§9.3） |
| GET | `/interpret` | — | `Map`（周期阶段 + 定性文本） | LLM 判定冰点/启动/主升/退潮，**按自然日缓存**；LLM 失败自动**降级规则法** |

### 6.8 ★ WatchlistController / TopicController（T20/T22）

| 方法 | 路径 | 请求体 | 说明 |
|---|---|---|---|
| GET | `/api/watchlist` | — | 自选股列表（含分组/标签/备注）；★ 每条内联行情速览 `latestPrice`/`changePct`/`quoteDate`（优先 `stock_quote` 最新一条，缺失时用日K收盘推算，无行情置 null 不报错） |
| POST | `/api/watchlist` | `Watchlist` | 新增；`groupName` 缺省“默认分组”，`tags` 逗号分隔 |
| PUT | `/api/watchlist/{id}` | 部分字段 | 修改分组/标签/备注 |
| DELETE | `/api/watchlist/{id}` | — | 删除（同时影响定时批处理范围，见 §9.4） |
| GET | `/api/topics` | — | 题材库：涨停池行业分布（`IndustryBucket`）+ LLM 题材解读（§9.3） |

### 6.9 ★ 个股深度增强组 — `/api/stock/{code}/*`（T23）

| 方法 | 路径 | 参数/请求体 | 说明 |
|---|---|---|---|
| GET | `/holders` | — | 十大股东 / 十大流通股东（按报告期，`holderType` 区分） |
| GET | `/holder-count` | `periods`（缺省 `DEFAULT_PERIODS`） | 股东户数与人均持股趋势 |
| GET | `/comparison` | — | 同行业对标：多股关键财务/估值指标并排 |
| GET | `/comparison/comment` | — | LLM 对标点评，按 **code + 当日**缓存，入库前 `stripNonBmp`（§5.8） |
| GET | `/notes` | `category?`（5 分类枚举） | 投资笔记列表 |
| POST / PUT / DELETE | `/notes`、`/notes/{id}` | `InvestNote` | 笔记 CRUD，正文 **1-10000 字**校验 |
| GET | `/alerts` | — | 价格预警列表（PRICE_ABOVE / PRICE_BELOW） |
| POST / PUT / DELETE | `/alerts`、`/alerts/{id}` | `PriceAlert` | 预警 CRUD；启用中且未触发的预警由 `AlertScanService` 每 5 分钟扫描（§9.4） |
| GET | `/signals` | — | 技术信号：MA5/MA20 金叉死叉 + 放量（>5 日均量 2 倍），**实时计算不入库**；K 线不足 21 根返 `available=false` |
| GET | `/lhb` | — | 个股龙虎榜上榜记录（只读复用 `StockLhbDetailRepository`） |

> ⚠️ **错误契约差异**：§6.4/§6.5 两个控制器位于 `com.stock.workflow.controller`，适用本模块的 `WorkflowControllerAdvice`（`{error,status,message}`）；§6.6~§6.9 位于 `com.stock.controller`，沿用**股票分析模块原有**的错误行为，不受模块级 Advice 影响（模块隔离原则，见 §5.6）。

## 7. SSE 事件协议

- **端点**：`GET /api/v1/workflow-execution/{processInstanceId}/stream`（`text/event-stream`）
- **事件名**：统一 `workflow-event`；**payload** 为 `WorkflowEvent` JSON（null 字段不序列化）：

```json
{
  "type": "NODE_COMPLETED",
  "processInstanceId": "xxx",
  "nodeId": "fundamental",
  "nodeName": "基本面分析",
  "delta": "（仅 NODE_DELTA）",
  "output": "（仅 NODE_COMPLETED，超 60000 字符截断）",
  "error": "（仅 NODE_FAILED / PROCESS_FAILED）",
  "timestamp": 1753689600000
}
```

- **类型枚举**：

| 类型 | 触发源 | 携带字段 |
|---|---|---|
| `NODE_STARTED` | Delegate 节点开始 | nodeId/nodeName |
| `NODE_DELTA` | AGENTSCOPE Runner TEXT_BLOCK_DELTA | nodeId/nodeName/delta |
| `NODE_COMPLETED` | Delegate 节点成功 | nodeId/nodeName/output |
| `NODE_FAILED` | Delegate 节点失败 | nodeId/nodeName/error |
| `PROCESS_COMPLETED` | Flowable PROCESS_COMPLETED 引擎事件 | — |
| `PROCESS_CANCELLED` | Flowable PROCESS_CANCELLED 引擎事件 | — |
| `PROCESS_FAILED` | Flowable JOB_EXECUTION_FAILURE 引擎事件 | error |

- **服务端语义**（`WorkflowSseService`）：
  - 订阅时**先回放**该实例全部历史事件再注册实时接收（晚到订阅者不丢事件）；流程已终止则回放完立即 `complete`；
  - `PROCESS_*` 三种为**终止事件**：推送后标记完成、complete 所有 emitter，状态保留 10 分钟供回放后清理；
  - 单个 emitter 超时 30 分钟；推送失败的 emitter 自动摘除。
- **前端策略**（`stores/workflow.ts`）：
  1. 进入执行页先 `GET /{pid}` 取快照归一化 nodeStates，再连 SSE（非终态时）；
  2. SSE 断线（EventSource CLOSED）**自动重连一次**，重连由服务端历史回放补齐丢失事件；
  3. 重连仍失败则**降级 5 秒轮询** `GET /{pid}`（connectionMode: sse → polling）；
  4. 收到终止事件或轮询到终态即断开（connectionMode=closed）。

## 8. 测试策略概述

| 层次 | 载体 | 覆盖点 |
|---|---|---|
| 后端单元测试 | `JsonToBpmnConverterTest`（16 例） | 校验规则全分支（id 非法/重复、dependsOn 引用、自依赖、环、孤立节点、agentId 存在性）；BPMN 结构断言（网关自动插入四规则、async 标记、delegateExpression、FieldExtension 仅 nodeId/workflowDefId） |
| 后端单元测试 | `WorkflowGeneratorServiceTest`（15 例） | 围栏剥离、JSON 解析失败、三重校验错误反馈重试、两次失败回退模板（fallback=true）、aiEdit 校验失败 400、PUBLISHED 置回 DRAFT、同名去重 |
| 后端集成测试 | `WorkflowEngineIntegrationTest`（6 例） | 独立 Flowable H2 内存引擎（不依赖 Spring/MySQL/LLM），beans map 注入假 `agentTaskDelegate`：串行顺序、并行分叉/汇聚、失败进死信（重试次数=1 保证确定性）、取消（停 AsyncExecutor 制造运行中状态）、流程变量传递 |
| 后端单元测试 | ★ 种子与启动预注入：`WorkflowSeedServiceTest`（14 例）+ `WorkflowRunServiceStartTest`（8 例） | 版本化 upsert 四分支（创建/低版升级/到位跳过/null 同名打标）与“用户数据不被触碰”不变式、PUBLISHED 自动重部署；STOCK_ANALYSIS 五上下文与 MARKET_REVIEW `_sentiment_context` 预注入与异常降级不中断 |
| 后端单元测试 | ★ 工具入参：`StockToolsParamTest`（10 例）+ `MarketToolsParamTest`（13 例） | 宽松解析与夹取（代码 6 位/days 区间/poolType 归一/日期缺省）、异常不外抛而返回 `[工具名] 错误描述` |
| 后端单元测试 | ★ 报告库：`ReportRunLogRepositoryTest`（7 例）+ `ReportLibraryServiceTest`（10 例）+ `ReportExportServiceTest`（5 例） | 原生 SQL 预览投影（规避 Hibernate 6 CLOB SUBSTRING 限制，见 §5.8）、分页筛选、Markdown/HTML 导出 |
| 后端单元测试 | ★ 市场域：`MarketDataControllerTest`（14）/ `MarketDataRepositoryTest`（7）/ `MarketSentimentServiceTest`（13）/ `MarketDataRealDbVerifyTest`（6） | 只读端点参数与空数据语义、findMaxTradeDate、情绪四项加权与断档降级、真实 MySQL 数据可用性 |
| 后端单元测试 | ★ 深度功能：`WatchlistControllerTest`（10）/ `InvestNoteControllerTest`（14）/ `StockComparisonServiceTest`（9）/ `TechnicalSignalServiceTest`（8）/ `AlertScanServiceTest`（7） | 自选股与笔记 CRUD、对标点评（含 stripNonBmp 净化）、MA 信号实时计算、预警命中与不重复告警 |
| 前端 E2E | Playwright `workflow-e2e.spec.ts`（TC-01~TC-06）+ `analysis-e2e.spec.ts`（4 例），共 10 条 | 菜单导航互切、工作流列表与状态标签、智能体 Tab、编辑器节点列表与 JSON 预览、发布/执行按钮状态、执行视图节点时间线 |
| 人工 Browser 验证 | 真实前后端联调 | 真实 LLM 节点执行、SSE 实时推流（NODE_DELTA 增量渲染）、断线重连与轮询降级、取消流程 |

后端共 **22 个测试类、207 个 `@Test`**（工作流模块 12 类/117 例 + 股票分析模块 10 类/90 例），`mvn test` 一次性全跑 **205 例**；两处例外需知晓：① 集成测试类名必须以 `*IntegrationTest` 后缀结尾才能命中 surefire 默认 includes；② ★ `SerperSearchServiceIT`（2 例，真实外网联通性）用 `*IT` 后缀 + `@EnabledIfSystemProperty(named="serper.it")`，**默认不参与 `mvn test`**，需 `-Dtest=SerperSearchServiceIT -Dserper.it=true -Dserper.api-key=xxx` 手动跑。E2E 依赖种子数据（`WorkflowSeedService` 版本化注入的 ★ 43 个智能体与 7 个种子工作流，见 §5.7/§5.7.1/§5.7.2）。

## 9. ★ 市场数据与智能体增强层设计（对标开盘啦功能补全，T19-T23）

本轮在工作流能力之外，补齐了“市场级数据 → 传统看盘视图 → 智能体驱动报告”的完整链路，共五个新菜单、三个市场域工作流、两个定时链路。目录与文件清单见 `CODE_STRUCTURE.md` §2/§3/§4/§6/§7。

### 9.1 市场数据管道（T19）

**链路**：`AKShare → data-fetcher/fetch_market.py 清洗归一化 → MySQL(stock_db) 直写 → 后端只读（MarketDataController / market_* 工具）→ 前端市场中心`。

**决策：数据抓取不经后端 API 直写 MySQL**。理由：① 与既有 `import_akshare.py` / `import_fundflow.py` 同一范式，无需改后端就能 T+1 补数据；② AKShare 依赖（pandas/akshare）只存于 Python 侧，不污染 JVM 依赖；③ 后端保持**纯只读**，避免写入路径双头。

| 数据类 | AKShare 接口 | 目标表 | 幂等策略 | 单位归一 |
|---|---|---|---|---|
| 涨停股池 | `stock_zt_pool_em`（今日）+ `stock_zt_pool_previous_em`（昨日） | `stock_zt_pool` | 唯一索引 `(code, trade_date, pool_type)` → `INSERT ... ON DUPLICATE KEY UPDATE` | 金额→元 |
| 龙虎榜 | `stock_lhb_detail_em` | `stock_lhb_detail` | **无**唯一索引（同股同日多行）→ 先按业务键查已存在行再插差集，**绕不 DELETE** | 金额→元 |
| 北向资金 | `stock_hsgt_hist_em(symbol="北向资金")` | `stock_north_flow` | 唯一索引 `(trade_date)` → upsert | →**亿元** |
| 融资融券 | `stock_margin_sse` + `stock_margin_szse` | `stock_margin_daily` | 唯一索引 `(trade_date, market)` → upsert | 深交所源数据为亿元，**×1e8 归一为元** |
| 大宗交易 | `stock_dzjy_mrmx(symbol="A股")` | `stock_block_trade` | **无**唯一索引（同股同日多笔）→ 同龙虎榜策略 | 金额→元 |

**抵制限流与建表**：每次 AKShare 请求间隔 `REQUEST_INTERVAL=5` 秒，失败按 `RETRY_BACKOFF=[5,10,20]` 秒指数退避重试；表不存在时脚本用 `CREATE TABLE IF NOT EXISTS` 建表，列名/类型/索引名**严格对齐 JPA 实体**（Hibernate camelCase→snake_case 命名策略），使后端 `ddl-auto=update` 可平滑接管、不会重复建表或改结构。

**脚本清单**（`data-fetcher/`）：

| 脚本 | 类型 | 职责 |
|---|---|---|
| `fetch_market.py` | 抓取 | 5 类市场数据主入口；支持 `--date` / `--only zt,lhb` / `--margin-days` / `--north-days` |
| `probe_akshare.py` | 探针 | 先探接口真实字段名与可用性，再写映射，避免盲写列名 |
| `verify_market_data.py` | 验证 | 入库后校对行数/字段非空率/单位量级 |
| `verify_market_dates.py` | 验证 | 校对各表有数据交易日连续性（识别漏抓） |
| `fetch_holders.py` / `probe_holders.py` / `verify_holders.py` | 抓取/探针/验证 | T23 股东数据（十大股东与股东户数）同三件套路 |

> ⚠️ 市场数据为 **T+1 盘后**口径；非交易日/未抓取日无数据时，接口返空数组、工具返“最新有数据日期”提示，**不 Mock 不插值**。

### 9.2 一级菜单与“传统视图 vs 智能体驱动”分界

前端 `App.vue` 菜单顺序（★ 为本轮新增）：

| # | 路由 | 菜单 | 视图 | 驱动方式 | 数据来源 |
|---|---|---|---|---|---|
| 1 | `/` | 股票分析 | `AnalysisView` / `StockDetailView` | 混合 | DB 真实行情 + LLM 分析 |
| 2 | ★ `/market` | 市场中心 | `MarketView`（4 子页签） | **传统视图**（零 LLM） | `/api/market/*` 只读查询 |
| 3 | ★ `/watchlist` | 自选股 | `WatchlistView` | **传统视图** | `/api/watchlist` CRUD |
| 4 | ★ `/topics` | 题材库 | `TopicsView` | **智能体驱动**（LLM 级联） | `/api/topics`（涨停池上下文 + DataEnricher） |
| 5 | ★ `/review` | 复盘中心 | `ReviewView` | **智能体驱动**（工作流） | 情绪仪 + 触发每日复盘工作流 |
| 6 | ★ `/reports` | 报告库 | `ReportsView` | 传统视图（检索智能体产物） | `/api/reports` 聚合 `workflow_run_log` |
| 7 | `/workflow` | 工作流管理 | `WorkflowListView` / `Editor` / `Execution` | 智能体驱动 | `/api/v1/workflow*` |
| 8 | `/chat` | 智能对话 | `ChatView` | 外部智能体（不经本项目后端） | 外部 Agent 项目 ChatService（8081），见 §10 |

**分界原则**（新增页面时遵循）：

1. **传统视图（看盘）**：只做“真实数据的查询与展现”，**绝不调 LLM**——响应必须是毫秒级、可任意刷新；数据空就展空态，不编造。例：市场中心、自选股、个股股东/龙虎榜/预警面板。
2. **智能体驱动（研究）**：产物是 LLM 生成的结论文本，耗时秒级~分钟级，**必须有缓存或异步作业**（当日缓存 / 工作流 + SSE），且 LLM 失败时靠**规则法降级**而不是报错白屏。例：题材库、复盘中心、对标点评。
3. 两类页面共享同一套底层表：传统视图的数据就是智能体工具（§3.1 market_*）的数据源，“看到什么”与“智能体看到什么”严格一致，便于人工核验报告可信度。

个股详情页（`StockDetailView`）重构为 **6 页签**：概览 / 股东追踪 / 财务对标 / 投资笔记 / 价格预警 / 龙虎榜，增强页签一律 `v-if="visited.xxx" v-show="activeTab==='xxx'"` **惰加载 + 已访问保持**，避免一次打开就并发 6 个接口。

### 9.3 情绪周期仪与题材库（T22）

**情绪评分**（`MarketSentimentService`，0-100 分）：四项加权 = 涨停家数 **30%** + 最高连板高度 **25%** + 封板率 **25%** + 两融余额环比 **20%**；满分基准 `ZT_FULL=120` 家 / `HEIGHT_FULL=7` 板 / `MARGIN_FULL_PCT=1.0`%；默认 `DEFAULT_DAYS=10`，上限 `MAX_DAYS=30`。

- **北向不参与计算**（数据 2024-08 停更，见 §5.8）；某日两融缺失时按**剩余权重归一化**，不把缺失当 0 分。
- `/interpret` 由 LLM 给出周期定性（冰点 / 启动 / 主升 / 退潮），**按自然日内存缓存**（`ConcurrentHashMap<LocalDate,…>`），当日重复调用不重复花钱；LLM 异常或超时自动**降级为规则法**（按得分区间映射阶段），接口永不因 LLM 不可用而 500。
- 情绪序列同时作为每日复盘工作流的预注入变量 `_sentiment_context`（§2.6），保证“仪表盘数字”与“复盘报告结论”同源。

**题材库**（`TopicService`）：以涨停池**行业分布**（`IndustryBucket`：行业/家数/最高连板/代表个股，`INDUSTRY_LIMIT=15`、`REP_STOCK_LIMIT=5`）为上下文，调 `DataEnricher` 级联生成题材解读；缓存**复用 `enrichment_data` 两级缓存 6 小时**，key 为 `stockCode="MARKET"`（恰好 6 字符，满足列长限制）+ `directionKey="TOPIC_yyyy-MM-dd"`；LLM 级联全部失败时**降级为仅返行业分布**。

测试覆盖：题材库与情绪仪均有纯 Mockito 单测（不连库、不调 LLM）——`TopicServiceTest` 验 Top15/代表股上限、buildContext 表格格式、enrich 缓存 key 契约与 fallback 降级；`MarketSentimentServiceTest` 验四项加权与规则法降级（§5 测试表）。

### 9.4 定时任务设计

`config/SchedulingConfig`（`@Configuration + @EnableScheduling`）为全局开关，两个定时链路共用（删除即静默失效，见 §5.8）：

| 任务 | 触发 | 行为 |
|---|---|---|
| `DailyScheduleService.scheduledRun` | `@Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Shanghai")` | 盘后批处理（下详） |
| `AlertScanService.scheduledScan` | `@Scheduled(fixedRate = 5 分钟, initialDelay = 60s)` | 扇描 `enabled=true && triggered=false` 预警，对照 `StockQuote` 最新价，命中则置 `triggered=true` 并记触发时间；同股行情一轮只查一次，无行情跳过；异常只记 error 不影响下轮 |

**盘后批处理三步**（`DailyScheduleService.runBatch`，严格串行）：

1. **数据抓取：显式跳过**——市场数据由 data-fetcher Python 脚本独立运行（依赖 AKShare 环境），本服务**不直接调用**，仅在摘要里记一条说明（避免 JVM 进程去 spawn Python 带来环境耦合）；
2. **自选股逐只串行触发股票分析工作流**：复用 `WorkflowRunService.start`，**前一只到达终态（COMPLETED/FAILED/CANCELLED）才启动下一只**，轮询间隔 `POLL_INTERVAL_MS=15s`、单股超时 `PER_STOCK_TIMEOUT_MINUTES=30`；
3. **触发每日复盘工作流**（`MARKET_REVIEW`，自带情绪预注入）。

**健壮性设计**：① 单线程 daemon 执行器（线程名 `daily-schedule`）+ `AtomicBoolean` 防重入，`/run-now` 在跑时返 `started=false`；② 任一工作流为 **DRAFT（未发布）时跳过该项并记原因**，不阻断其余步骤；③ 每步结果进 `lastRunSummary`（`state/source/startedAt/finishedAt/steps`）供 `/api/schedule/status` 查询；④ 为不占用请求线程，触发接口立即返回、批处理异步执行。

> 法定节假日无当日数据时不特殊处理：工作流自会基于“最新有数据交易日”降级输出（§9.1）。

测试覆盖：`DailyScheduleServiceTest`（纯 Mockito，等待/轮询参数包级可注入缩短）覆盖自选股为空跳过、两股串行触发与 COMPLETED/FAILED 计数、等终态超时 TIMEOUT、triggerRun 防重入；`ScheduleControllerTest`（standalone MockMvc）验 /run-now 与 /status 响应结构。

### 9.5 个股深度增强设计要点（T23）

- **股东追踪**：`stock_holder_top`（十大股东/十大流通股东，唯一索引 `(code, report_date, holder_type, holder_rank)`）+ `stock_holder_count`（唯一索引 `(code, stat_date)`），数据由 `fetch_holders.py` 直写，后端只读。
- **财务对标**：`StockComparisonService` 先组装确定性的指标并排表（无 LLM），点评单独一个接口、按 **code + 当日**缓存，入库前 `stripNonBmp`（§5.8）——保证“看数据快、要结论才慢”。
- **投资笔记**：`InvestNote` 5 个分类枚举（INVEST_LOGIC / RISK_POINT / BUY_CONDITION / SELL_CONDITION / FREE_NOTE），正文 1-10000 字校验，索引 `(code, updated_at DESC)` 支持按股倒序列表。
- **价格预警 vs 技术信号的职责分开**：预警（`PriceAlert`）是**有状态**的——需要被定时扇描并标记 `triggered`，故入库；技术信号（`TechnicalSignalService`，`/signals`）是**无状态派生值**——MA5/MA20 金叉死叉与放量（`VOLUME_SURGE_RATIO=2.0`，对比 5 日均量）完全由近 60 根日 K 线实时计算，**不建表不入库**（避免双写与计算口径漂移）；K 线不足 21 根时返 `available=false` 而不是猜一个信号。
- **个股龙虎榜**：`StockLhbController` 零新表，直接只读复用市场域 `StockLhbDetailRepository`（市场数据与个股视图双向复用的典型例子）。


## 10. 智能对话菜单（外部 Agent 项目 ChatService 集成）

> 前端纯集成功能：本项目后端（8080）不参与，前端直连外部 Agent 项目（`D:\code\Agent`，Spring Boot，端口 **8081**）的 ChatService REST API。

- **入口**：顶部导航“智能对话”→ 路由 `/chat` → `ChatView.vue`；API 封装在 `api/chat.ts`。
- **对端接口**（base `http://localhost:8081/api/v1`，Agent 侧 CORS 默认 `*`、开发模式鉴权关闭）：
  | 方法 | 路径 | 说明 |
  |---|---|---|
  | POST | `/chat/stream` | 流式对话，请求体 `{userId, conversationId?, message}`；响应 `text/event-stream`，每块一行 JSON：`{type:meta,conversationId}` → `{type:delta,content}`×N → `{type:done}` / `{type:error,message}` |
  | GET | `/conversations?userId=xx` | 会话列表（updatedAt 倒序） |
  | GET | `/conversations/{id}/messages` | 会话消息（时间升序） |
- **流式消费实现**：非标准 SSE（POST + 行式 JSON），EventSource 不适用；`streamChat` 用 `fetch` + `ReadableStream` 按 `\n` 分帧解析，支持 AbortController 取消（组件卸载时 abort）。
- **身份**：Agent 侧 `auth.enabled=false` 时信任请求体 userId；前端取 `localStorage.chat_user_id`，缺省 `small`。
- **交互**：左侧会话列表（点击加载历史消息）+ 新建对话；右侧气泡式消息流，delta 增量实时渲染；流结束（done/error/自然断流）统一走 `finishStream` 收尾：已生成部分追入消息列表并刷新会话列表（与 Agent 侧“部分回复也持久化”语义对齐）。

### 10.1 行情数据工具桥（方案A，改动全部在 Agent 项目侧）

> 解决“问行情答无数据”问题：对话智能体此前未挂任何工具，模型只能声明无实时数据。方案A 给 Agent 项目的对话智能体注册 4 个 AgentScope 工具，工具内部 HTTP **只读**调用本项目后端（8080）的市场数据接口；另配套移植一个 `web_search` 实时搜索工具补齐 T+1 时效短板（见 10.1.1）。本项目 8080 侧**零代码改动**。

- **新增 `com.agent.chat.tools.StockMarketTools`**（`@Component`，`@Tool`/`@ToolParam` 注解 + `Toolkit.registerTool`，agentscope-harness 2.0.0-RC2，与本项目 `StockKlineTool` 同一机制）：
  | 工具 | 对端接口（8080） | 用途 |
  |---|---|---|
  | `market_overview` | `GET /api/market/sentiment?days=N` + `GET /api/market/sentiment/interpret` | 市场情绪分快照 + LLM 周期定性（回答“今天A股行情怎样”的主力工具） |
  | `market_zt_pool` | `GET /api/market/zt-pool?poolType=&date=` | 涨停股池/连板梯队（最多展示 20 条控 token） |
  | `market_lhb` | `GET /api/market/lhb?date=` | 龙虎榜净买额榜（最多展示 20 条） |
  | `stock_quote` | `GET /api/stock/{code}/quote` | 个股最新行情快照（404 → 提示未导入本地库） |
- **契约约定**：数据为 AKShare T+1 盘后口径（回复中强制注明数据日期）；工具异常不外抛，统一返回 `[工具名] 错误描述` 文本交给模型处置；查无数据返回说明文本，绝不 Mock；`brokenRate`/`sealRate` 对端已是百分数值，工具直接展示不再乘 100。
- **接线**：`AgentFactory` 新增带 `Toolkit` 的重载（`disableFilesystemTools/ShellTool/MemoryTools`，`maxIters=6`）；`GeneralChatOrchestrator` 同步/流式两条路径均挂工具并重写 SYS_PROMPT（工具说明+数据边界）；`ChatService` 的 `stock_analysis` 意图策略提示改为“优先调用行情工具取数后作答，盘中/最新消息叠加 web_search”（`document_analysis` 拆出维持原声明边界语义）。
- **配置**：Agent 侧 `application.yml` 新增 `stock-api.base-url: http://localhost:8080` 与 `serper.*`（`@Value` 均有默认值）。
- **前端零改动**：ChatController 流式循环只消费 `TextBlockDeltaEvent`，工具调用事件天然被过滤，不会泄漏到 delta 流。

#### 10.1.1 web_search 实时搜索工具（补齐 T+1 时效短板）

> 对话场景经常需要实时信息（盘中行情/最新公告/政策舆情），本地库 T+1 盘后口径覆盖不到，因此把本项目工作流侧的 `web_search` 工具同款移植到 Agent 项目。

- **新增 `com.agent.chat.tools.WebSearchTool`**（`web_search`）：Serper.dev（Google 搜索 API）直连，不经 8080 中转；与本项目 `WebSearchTool`/`SerperSearchService` 同一契约：POST `/search|/news`（`gl=cn, hl=zh-cn`），结果按 answerBox / knowledgeGraph / organic|news 三段拼装为紧凑文本（单条 snippet 截 300 字符）。
- **分工约定**（已写入 SYS_PROMPT 与 stock_analysis 策略提示）：本地工具给盘后结构化数据，`web_search` 补盘中/最新动态；搜索结果引用时注明来源与日期，与本地库冲突时以数据日期新者为准并说明差异。
- **配置**：Agent 侧 `application.yml` `serper.api-key|base-url|num`，与本项目后端同一 Serper 账号，支持 `SERPER_API_KEY` 环境变量覆盖；未配置 key 时工具返回说明文本不报错。
- **验证结果**：问“A股今天最新消息和盘中动态”，智能体同时调本地工具（7-29 情绪分）+ web_search（当日实时新闻），并在回复中区分两种数据口径。

