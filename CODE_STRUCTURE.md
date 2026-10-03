# StockTradingSystem 代码结构说明（CODE_STRUCTURE.md）

> 技术栈：后端 Spring Boot 3.4.3 + Java 17 + MySQL + Spring Data JPA + AgentScope harness 2.0.0-RC2 + Flowable 7.2.0 + commonmark 0.24.0；前端 Vue 3 + TypeScript + Vite + Pinia + Vue Router + Playwright；数据管道 Python + AKShare。
> 本文档描述项目当前真实目录结构，重点展开工作流管理模块（后端 `com.stock.workflow` 包 + 前端工作流三视图）与本轮“功能补全蓝图”（对标开盘啦，T19-T23）新增的市场数据管道、市场中心/自选股、报告库、智能体增强层与个股深度增强。

## 1. 顶层目录树

```
StockTradingSystem/
├── backend/                          # Spring Boot 后端（股票分析 + 工作流管理）
│   ├── pom.xml                       # Maven 配置（Spring Boot 3.4.3 / agentscope-harness 2.0.0-RC2 / flowable 7.2.0 / ★ commonmark 0.24.0 报告导出渲染）
│   └── src/
│       ├── main/
│       │   ├── java/com/stock/
│       │   │   ├── StockApplication.java        # 启动类（含 /api/** 全局 CORS 配置）
│       │   │   ├── agent/                       # 【股票分析】AgentScope 智能体工厂与分析方向枚举
│       │   │   ├── config/                      # 模型三组配置 AgentModelConfig / 异步线程池 AsyncConfig / ★ SchedulingConfig（@EnableScheduling 全局定时任务开关）/ ★ SerperConfig（Serper.dev 搜索 API 配置）
│       │   │   ├── controller/                  # 分析与行情 REST 控制器 + ★ 本轮 8 个新控制器（市场数据/情绪/自选股/题材/股东/对标/笔记/预警/个股龙虎榜，详见 §4.1）
│       │   │   ├── dto/                         # （当前为空）
│       │   │   ├── entity/                      # 股票/财务/资金流/分析会话等 JPA 实体，★ 本轮新增 10 个（5 市场数据 + 自选股/笔记/预警/股东×2，详见 §6.1）
│       │   │   ├── repository/                  # 实体一一对应的 JPA Repository（★ 同步新增 10 个）
│       │   │   ├── service/                     # 编排/分析引擎/数据补全等服务 + ★ 本轮新增 5 个（情绪/题材/对标/预警扫描/技术信号，详见 §4.2）
│       │   │   └── workflow/                    # 【工作流模块】详见 §2（★ 本轮新增报告库/定时批处理/市场域种子/市场工具）
│       │   └── resources/
│       │       └── application.yml              # 数据源(Hikari 30/10) / Flowable 异步执行器(core 8/max 16) / agent.model.* 三组模型配置
│       │                                        #   ★ 密钥管理：真实 API Key 一律不入库，放 backend/application-local.yml（gitignore，
│       │                                        #   经 spring.config.import: optional:file:./application-local.yml 加载）或环境变量
│       │                                        #   DEEPSEEK_API_KEY / KIMI_API_KEY / SERPER_API_KEY；AnalysisEngineService 密钥改走 @Value 配置链
│       └── test/java/com/stock/                 # 后端测试（工作流模块 12 类 + 股票分析模块 10 类，详见 §5）
│           ├── workflow/engine/                 # JsonToBpmnConverterTest / WorkflowEngineIntegrationTest / WorkflowRunServiceStartTest / StockContextPreloaderTest / WorkflowTextUtilsTest / tools（★ Stock、MarketToolsParamTest）
│           ├── workflow/service/                # WorkflowGeneratorServiceTest / ★ WorkflowSeedServiceTest / ★ ReportLibraryServiceTest / ★ ReportExportServiceTest
│           ├── workflow/repository/             # ★ ReportRunLogRepositoryTest
│           └── controller|service|repository/    # ★ 股票分析模块新增测试（市场数据/自选股/笔记/情绪/对标/信号/预警/Serper IT）
├── frontend/                         # Vue 3 前端
│   ├── src/                          # 源码（详见 §3）
│   ├── e2e/                          # Playwright 端到端测试
│   ├── public/                       # 静态资源（favicon.svg / icons.svg）
│   ├── vite.config.ts / playwright.config.ts / tsconfig*.json / package.json
│   └── index.html
├── data-fetcher/                     # Python FastAPI 行情数据抓取服务（core/routers/tests）
│   ├── fetch_market.py               # ★ 市场级数据抓取（AKShare→MySQL 直写，不经后端 API）：涨停池/龙虎榜/北向/两融/大宗 5 类，T+1 盘后运行；
│   │                                 #   幂等（唯一索引 upsert / 无唯一索引先查差集再插，绝不 DELETE）、限速 ≥3s + 指数退避、金额单位归一化
│   ├── probe_akshare.py              # ★ AKShare 市场接口探测（字段/单位/可用性验证）
│   ├── verify_market_data.py         # ★ 市场数据入库校验（行数/金额单位/关键字段抽查）
│   ├── verify_market_dates.py        # ★ 各市场表可用交易日核对
│   ├── fetch_holders.py              # ★ 股东数据抓取（十大股东/十大流通股东/股东户数 → stock_holder_top / stock_holder_count）
│   ├── probe_holders.py              # ★ AKShare 股东接口探测
│   └── verify_holders.py             # ★ 股东数据入库校验
├── agent-scope/                      # AgentScope 学习与实验性 Demo 工程（独立 pom）
├── docs/                             # 设计文档（design/PRD 等）与半导体知识库
├── scripts/                          # 启停与冒烟测试脚本
└── data/                             # 本地 H2 数据文件（agent_os.mv.db，AgentOS 画像服务用）
```

## 2. 后端工作流模块：`com.stock.workflow`

```
com.stock.workflow/
├── entity/                               # JPA 实体（3 张业务表，见 §6）
│   ├── WorkflowDef.java                  # 工作流定义：name/category/definitionJson/processDefinitionKey/deploymentId/status/version/createdBy/seedVersion(★新增)
│   ├── AgentDef.java                     # 智能体定义：type(PROMPT|AGENTSCOPE)/systemPrompt/modelChoice/temperature/maxTokens/toolsJson/maxIterations/loopDepth/eventsJson/seedVersion(★新增)
│   └── WorkflowRunLog.java               # 节点执行日志：processInstanceId/nodeId/status/inputText/outputText/errorMessage/startedAt/completedAt
├── repository/                           # Spring Data JPA 仓储
│   ├── WorkflowDefRepository.java        # findByName 等
│   ├── AgentDefRepository.java           # findByName 等
│   ├── WorkflowRunLogRepository.java     # findByProcessInstanceIdOrderByStartedAtAsc
│   └── ReportRunLogRepository.java       # ★ 报告库专用只读查询（与 RunLog 同实体独立文件）：按 process_instance_id 分组的运行级汇总投影 +
│                                         #   预览列原生 SQL SUBSTRING(output_text,1,200)（Hibernate 6 禁止 JPQL 对 CLOB 用 SUBSTRING，见 DESIGN.md §5.8）
├── engine/                               # 工作流引擎核心：JSON→BPMN 转换、部署、运行、SSE
│   ├── WorkflowJsonDefinition.java       # 简化工作流 JSON 顶层载体（name/nodes）
│   ├── WorkflowJsonNode.java             # 简化 JSON 节点（id/name/agentId/promptTemplate/timeoutSeconds/dependsOn）
│   ├── JsonToBpmnConverter.java          # 简化 JSON 校验（唯一性/环/引用/孤立节点）+ 转 BpmnModel（自动插并行网关）
│   ├── WorkflowDeployService.java        # 部署：JSON→校验→BpmnModel→Flowable ProcessValidator→部署→回写元数据(PUBLISHED)
│   ├── StockContextPreloader.java        # ★ 股票数据预注入：从 MySQL 真实数据拼装 5 个 _xxx_context 提示词文本上下文
│   │                                     #   （K线/财务/资金流/行业/公司，单类缺失填“（暂无该类数据）”，绝不抛异常、严禁 Mock）
│   ├── WorkflowRunService.java           # 启动/取消流程实例 + 状态合成视图（RunLog 为主、HistoricActivityInstance 兜底；启动输入净化非 BMP，输出截断 60000）
│   │                                     #   start() 对 STOCK_ANALYSIS 类工作流：★ 先走数据准备闸门（StockDataAcquisitionService 盘点+自动补数：
│   │                                     #   行情 Serper 首选、日K线腾讯→新浪→Kimi、财务/资金流东财；
│   │                                     #   新鲜度规则：最新K线距今超 7 自然日视为过期重拉（upsert 幂等），
│   │                                     #   K线拉不到则抛异常阻断启动不烧 token），再调 StockContextPreloader 预注入并从 DB 回填 stockName
│   ├── AgentTaskDelegate.java            # ServiceTask JavaDelegate：反查节点配置→占位符插值→分发两类 Runner→输出净化非 BMP 后写变量/日志→推 SSE
│   ├── WorkflowRunLogWriter.java         # RunLog 写入器（REQUIRES_NEW 独立事务，防流程回滚吞日志；入库文本统一净化非 BMP）
│   ├── WorkflowTextUtils.java            # 文本工具：非 BMP 字符净化（emoji 防 MySQL utf8 三字节写入失败）+ 统一截断阈值 60000
│   ├── WorkflowSseService.java           # SSE 推送服务：事件历史回放 + 广播 + 终止清理（事件名 workflow-event）
│   ├── WorkflowEvent.java                # SSE 事件载体（7 种事件类型枚举常量）
│   ├── WorkflowStatusView.java           # 状态合成视图 DTO（流程级状态 + 节点级 NodeStatusView 列表）
│   └── GlobalWorkflowEventListener.java  # Flowable 全局事件监听器：流程完成/取消/Job 失败 → 流程级 SSE 事件
├── engine/agent/                         # 两类智能体执行器
│   ├── PromptAgentRunner.java            # PROMPT 型：非流式 OpenAIChatModel + HarnessAgent.call().block()，禁用全部内置工具
│   └── AgentScopeAgentRunner.java        # AGENTSCOPE 型：流式 HarnessAgent.streamEvents()，工具白名单裁剪 + maxIters + delta 回调
│                                         #   ★ 按 AgentDef.toolsJson 将自定义股票工具选择性 Toolkit.registerTool（内置组仍走 disableXxx 裁剪）
├── engine/tools/                         # ★ 本轮新增：AgentScope 自定义工具（@Tool 注解 Spring Bean，真实 DB 数据）
│   ├── StockKlineTool.java               # stock_kline：日K线摘要（MA5/20/60、量价特征、近30日明细；参数 stockCode, days）
│   ├── StockFinanceTool.java             # stock_finance：最近多期 EPS/ROE/营收/净利及同比（参数 stockCode）
│   ├── StockFundFlowTool.java            # stock_fundflow：近N日主力净流入序列与趋势（参数 stockCode, days）
│   ├── StockIndustryTool.java            # stock_industry：行业/产业链定位、主营与竞品对比（参数 stockCode）
│   ├── DataEnrichTool.java               # data_enrich：优先 Serper 网络搜索，再由外部 LLM 级联（DeepSeek→Kimi）提炼；enrichment_data 6小时缓存；参数 stockCode, topic
│   ├── WebSearchTool.java                # ★ web_search：Serper.dev（Google 搜索 API）实时网络搜索，系统默认优先数据源；参数 query, type 可选 search|news，readOnly
│   ├── MarketZtTool.java                 # ★ market_zt：涨停股池查询（涨停家数/连板梯队/炸板统计/行业分布/个股明细含 ztStat；参数 date 可选, poolType 可选 TODAY|PREVIOUS，readOnly）
│   ├── MarketLhbTool.java                # ★ market_lhb：龙虎榜查询（按日期查当日上榜个股净买额降序 / 按代码查个股近期上榜记录；参数 date 可选, stockCode 可选，readOnly）
│   └── ToolParams.java                   # 工具入参解析辅助（股票代码 6 位校验、days 宽松解析并夹取区间）
├── config/
│   └── WorkflowEngineConfig.java         # 向 Flowable 引擎注册 GlobalWorkflowEventListener
├── service/                              # 业务服务
│   ├── WorkflowGeneratorService.java     # AI 生成/AI 编辑工作流：LLM 输出简化 JSON → 三重校验 → 重试/回退模板
│   │                                     #   生成提示词注入五段式“节点提示词质量标准”（PROMPT_QUALITY_DOC）；回退模板升级为三节点（规划→执行→复核）
│   ├── WorkflowGenerationResult.java     # 生成结果载体（workflow/fallback/message）
│   ├── SeedAgentPrompts.java             # 24 个种子智能体 systemPrompt 常量（开发域 8 + 股票分析域 11 + 博客域 5）
│   ├── SeedNodePrompts.java              # 27 个种子节点 promptTemplate 常量（股票 11 + 开发 9 + 博客 7，均为五段式结构化提示词）
│   ├── SeedMarketPrompts.java            # ★ 本轮新增：市场域种子提示词常量（9 个智能体 systemPrompt + 13 个节点 promptTemplate，五段式；
│   │                                     #   服务于每日复盘/题材挖掘/组合诊断三个种子工作流，见 DESIGN.md §5.7.1）
│   ├── SeedDevTeamPrompts.java           # ★ 本轮新增：功能开发协作域种子提示词常量（10 个智能体 systemPrompt + 14 个节点 promptTemplate，五段式；
│   │                                     #   服务于「功能开发协作工作流」v1，入口变量 ${requirement}，见 DESIGN.md §5.7.2）
│   ├── WorkflowSeedService.java          # 种子数据版本化升级（seedVersion 受控原地升级，PUBLISHED 自动重新部署，绝不清库/删用户数据）：
│   │                                     #   ★ 本轮扩充至 43 个 AgentDef（+9 市场域 +10 功能开发协作域）与 7 个 WorkflowDef
│   │                                     #   （+每日复盘 v1 / 题材挖掘 v1 / 组合诊断 v1 / 功能开发协作 v1，后者 14 节点 3 处并行汇聚）
│   ├── ReportLibraryService.java         # ★ 本轮新增：报告库检索（运行粒度分页 + 股票/工作流/时间筛选）与单次运行节点明细组装
│   ├── ReportExportService.java          # ★ 本轮新增：报告导出（Markdown 直接拼装；HTML 用 commonmark 0.24.0 渲染后套简洁模板，escapeHtml）
│   ├── ReportRunSummary.java             # ★ record DTO：运行级汇总（列表页行）
│   ├── ReportNodeDetail.java             # ★ record DTO：节点级明细（含输出全文与耗时）
│   ├── ReportRunDetail.java              # ★ record DTO：单次运行详情（汇总 + 节点列表）
│   └── DailyScheduleService.java         # ★ 本轮新增：盘后每日批处理（交易日 17:00 Asia/Shanghai：自选股逐只串行触发股票分析工作流→触发每日复盘工作流；
│                                         #   单线程执行器 + AtomicBoolean 防重入，单股超时 30 分钟，支持 run-now 手动触发，见 DESIGN.md §9.4）
└── controller/                           # REST 控制器（契约详见 DESIGN.md §6）
    ├── WorkflowController.java           # /api/v1/workflow：定义 CRUD / 发布 / AI 生成 / AI 编辑 / 执行
    ├── AgentDefController.java           # /api/v1/workflow-agent：智能体 CRUD + 可用工具组查询（★ 自定义股票工具白名单增至 8 个）
    ├── WorkflowExecutionController.java  # /api/v1/workflow-execution：状态 / SSE 流 / 取消 / 历史实例
    ├── ReportLibraryController.java      # ★ /api/reports：报告库分页检索 / 运行详情 / 导出 md|html（见 DESIGN.md §6.4）
    ├── ScheduleController.java           # ★ /api/schedule：POST /run-now 手动触发批处理 / GET /status 最近一次执行摘要（见 DESIGN.md §6.5）
    └── WorkflowControllerAdvice.java     # 模块级统一异常处理（{error,status,message}，仅作用于本包）
```

### 各包职责一句话说明

| 包 | 职责 |
|---|---|
| `workflow.entity` | 3 张业务表的 JPA 实体（工作流定义、智能体定义、节点执行日志），`ddl-auto: update` 自动建表 |
| `workflow.repository` | 实体对应的 Spring Data JPA 仓储接口，含按名查重与按流程实例查日志的派生查询；★ 新增 `ReportRunLogRepository`（报告库只读聚合查询，原生 SQL SUBSTRING 预览投影） |
| `workflow.engine` | 引擎核心层：简化 JSON 的校验/转换、部署、运行（含 STOCK_ANALYSIS 真实数据预注入与 ★ MARKET_REVIEW 情绪指标预注入 `_sentiment_context`）、状态合成、SSE 推流、Flowable 事件桥接 |
| `workflow.engine.agent` | 节点智能体执行器：PROMPT（一次性 LLM 调用）与 AGENTSCOPE（多轮推理 + 工具，含自定义工具动态注册）两种 Runner；★ `CUSTOM_STOCK_TOOLS` 白名单扩至 8 项 |
| `workflow.engine.tools` | AgentScope 自定义工具：★ 8 个 `@Tool` 注解 Spring Bean（stock_kline / stock_finance / stock_fundflow / stock_industry / data_enrich / market_zt / market_lhb + 本轮新增 web_search），全部读真实数据；web_search 走 Serper.dev（Google 搜索），market_* 复用市场数据表（§6.1） |
| `workflow.engine.config`（实际为 `workflow.config`） | 引擎装配：通过 `EngineConfigurationConfigurer` 注册全局事件监听器 |
| `workflow.service` | 业务服务：AI 生成/编辑工作流、种子数据版本化升级（★ 43 智能体 + 7 工作流）与四个提示词常量类（SeedAgentPrompts / SeedNodePrompts / ★ SeedMarketPrompts / ★ SeedDevTeamPrompts）；★ 新增报告库检索/导出（ReportLibraryService / ReportExportService + 3 个 record DTO）与盘后定时批处理（DailyScheduleService） |
| `workflow.controller` | REST 入口★五控制器（新增 ReportLibraryController / ScheduleController）+ 模块级 `@RestControllerAdvice` 错误契约 |

## 3. 前端结构：`frontend/src`

```
frontend/src/
├── main.ts / App.vue / style.css         # 应用入口、根组件（★ 顶部菜单扩至 8 项：股票分析 | 市场中心 | 自选股 | 题材库 | 复盘中心 | 报告库 | 工作流管理 | 智能对话）、全局样式
├── router/index.ts                       # 路由：/ 股票分析、/stock/:code 个股详情、
│                                         #      ★ /market 市场中心、/watchlist 自选股、/reports 报告库、/topics 题材库、/review 复盘中心、/chat 智能对话、
│                                         #      /workflow 工作流列表、/workflow/:id/edit 编辑器、/workflow/execution/:processInstanceId 执行视图
│                                         #   ★ ReportsView 用 import.meta.glob 接线（规避 Vite 静态 import 不存在文件导致全站白屏，见 DESIGN.md §5.8）
├── types/
│   ├── analysis.ts                       # 【股票分析】分析会话/结果类型
│   └── workflow.ts                       # 【工作流】WorkflowDef/AgentDef/WorkflowEvent/NodeRuntimeState 等全套类型
├── api/
│   ├── stock.ts                          # 【股票分析】行情与分析 REST 封装
│   ├── sse.ts                            # 【股票分析】分析会话 SSE 封装
│   ├── workflow.ts                       # 【工作流】三组 axios 实例（workflow / workflow-execution / workflow-agent）
│   │                                     #   + connectExecutionStream SSE（EventSource 监听 workflow-event）
│   ├── market.ts                         # ★【市场中心】/api/market 封装：涨停池/龙虎榜/北向/两融/大宗/可用日期 + 情绪指数与 LLM 定性
│   ├── watchlist.ts                      # ★【自选股】/api/watchlist CRUD（分组/标签）
│   ├── reports.ts                        # ★【报告库】/api/reports 分页检索/详情/导出封装
│   ├── topics.ts                         # ★【题材库】/api/topics 封装
│   ├── chat.ts                           # ★【智能对话】对接外部 Agent 项目 ChatService（http://localhost:8081/api/v1）：
│   │                                     #   会话列表/消息查询 axios 封装 + streamChat（fetch 读取 POST /chat/stream 行式 JSON 流：meta/delta/done/error）
│   └── stockDetail.ts                    # ★【个股深度】股东/对标/笔记/预警/信号/个股龙虎榜 REST 封装
├── stores/
│   ├── stock.ts / analysis.ts            # 【股票分析】Pinia store
│   └── workflow.ts                       # 【工作流】列表/编辑/执行状态；SSE 事件归约到 nodeStates，
│                                         #   断线重连一次失败后降级 5 秒轮询
├── views/
│   ├── AnalysisView.vue                  # 【股票分析】主分析页
│   ├── StockDetailView.vue               # 【股票分析】个股详情页（★ 本轮重写为 6 页签：概览/股东/对标/笔记/预警/龙虎榜，增强页签懒加载——首次切入才挂载）
│   ├── StockListView.vue                 # 【股票分析】股票列表页
│   ├── MarketView.vue                    # ★【市场中心】4 子页签：涨停池/龙虎榜/北向与两融/大宗交易（共用 MarketDatePicker 交易日选择）
│   ├── WatchlistView.vue                 # ★【自选股】自选股管理（CRUD + 分组/标签）
│   ├── ReportsView.vue                   # ★【报告库】分页检索（按股票/工作流/时间）+ 运行节点明细 + 导出 md|html
│   ├── TopicsView.vue                    # ★【题材库】当日涨停池行业分布 + LLM 热点题材挖掘展示
│   ├── ReviewView.vue                    # ★【复盘中心】情绪周期仪（情绪分序列 + LLM 定性）+ 每日复盘工作流触发与报告查看
│   ├── ChatView.vue                      # ★【智能对话】/chat：左侧会话列表 + 右侧对话区（流式渲染增量回复），调用 Agent 项目 ChatService（8081），
│   │                                     #   userId 取 localStorage.chat_user_id（默认 small）
│   │                                     #   ★ Agent 侧对话智能体已挂 4 个行情工具（StockMarketTools，HTTP 只读调本项目 8080：情绪总览/涨停池/龙虎榜/个股快照）
│   │                                     #   + web_search 实时搜索（WebSearchTool，Serper.dev 直连，补盘中/最新消息），
│   │                                     #   行情类提问基于真实数据作答，见 DESIGN.md §10.1（本项目 8080 侧零代码改动）
│   ├── WorkflowListView.vue              # 【工作流】视图一：工作流列表 + 智能体 Tab（CRUD/发布/执行/AI 生成入口）
│   │                                     #   执行弹窗仅提取用户级占位符，自动跳过下划线开头的后端预注入变量（如 _kline_context）
│   ├── WorkflowEditorView.vue            # 【工作流】视图二：节点编辑器（节点表单 + JSON 预览 + AI 编辑；执行弹窗同样跳过 _ 前缀变量）
│   └── WorkflowExecutionView.vue         # 【工作流】视图三：执行视图（节点时间线 + 实时输出 + 取消）
└── components/                           # 【股票分析】组件（AnalysisCard/AnalysisDashboard/DirectionSelector/
    │                                     #   KlineChart/ModelSelector/StockResolver/ThinkingProcess）
    ├── market/                           # ★【市场中心】子面板：MarketDatePicker（交易日选择，基于 /api/market/dates）/
    │                                     #   ZtPoolPanel（涨停池，含今日/昨日池切换）/ LhbPanel（龙虎榜）/ NorthMarginPanel（北向+两融）/ BlockTradePanel（大宗）
    └── stock/                            # ★【个股深度】页签面板：HoldersPanel（十大股东/股东户数）/ ComparisonPanel（财务对标+LLM 点评）/
                                          #   NotesPanel（投资笔记 CRUD）/ AlertsPanel（价格预警+技术信号）/ LhbPanel（个股龙虎榜）

frontend/e2e/
├── analysis-e2e.spec.ts                  # 股票分析 E2E（4 条用例）
└── workflow-e2e.spec.ts                  # 工作流 E2E（TC-01~TC-06，6 条用例）
```

### 前端各目录职责一句话说明

| 目录 | 职责 |
|---|---|
| `types/` | TS 类型定义，`workflow.ts` 与后端实体/SSE 事件字段一一对应 |
| `api/` | axios REST 封装与 SSE（EventSource）连接工厂；★ 本轮新增 market / watchlist / reports / topics / stockDetail 五个封装文件，覆盖全部新后端端点 |
| `stores/` | Pinia 状态层，`workflow.ts` 负责 SSE 事件归约、快照合并（先 GET 快照再连 SSE 回放）与轮询降级；新菜单页面状态轻量，组件内 ref 管理，未新增 store |
| `views/` | 页面视图：工作流三视图 + ★ 本轮新增 MarketView / WatchlistView / ReportsView / TopicsView / ReviewView 五视图，StockDetailView 重写为 6 页签 |
| `components/market/` | ★ 市场中心 5 个子面板（日期选择 + 4 类数据面板） |
| `components/stock/` | ★ 个股深度 5 个页签面板（股东/对标/笔记/预警/龙虎榜） |
| `e2e/` | Playwright 端到端测试（工作流 6 条 + 股票分析 4 条，共 10 条） |

## 4. 股票分析模块（`com.stock.*`）

- **agent/**：`AgentFactory`（构建 DeepSeek/Kimi 流式模型与 HarnessAgent）、`AnalysisDirection`（十大分析方向枚举）、`ModelChoice`、`AnalysisStreamEvent`、`ProfileClient`（调用 AgentOS 画像服务）、`StockCandidate`。
- **config/**：`AgentModelConfig`（`agent.model.deepseek-v4 / deepseek-v5 / kimi` 三组配置，工作流两类 Runner 复用）、`AsyncConfig`、★ `SchedulingConfig`（`@EnableScheduling`，全局定时任务开关；`DailyScheduleService` 与 `AlertScanService` 均依赖它生效）、★ `SerperConfig`（`@ConfigurationProperties(prefix=serper)`，api-key 走 `${SERPER_API_KEY:...}` 环境变量覆盖）。
- **controller/**：`AnalysisAgentController`（多智能体分析会话 + SSE）、`AnalysisController`、`StockAnalysisController`、`StockDataController`（含 `GET/POST /api/stock/{code}/fundflow` 资金流查询与批量入库，code+tradeDate 幂等 upsert；★ `POST /api/stock/{code}/acquire` 手动触发数据准备闸门补数，不启动工作流不烧 token）、`GlobalExceptionHandler`；★ 本轮新增 8 个控制器（见 §4.1）。
- **entity/repository/**：股票基础/行情/K 线/财务/产业链/竞争对手/产品拆分/补全缓存/资金流/分析会话与结果等实体-仓储对；★ 本轮新增 10 对（5 市场数据表 + Watchlist / InvestNote / PriceAlert / StockHolderTop / StockHolderCount，详见 §6.1）。
- **service/**：`AgentOrchestrationService`（十域智能体编排与会话 SSE 管理）、`AnalysisEngineService`、`DataContextBuilder`、`DataEnricher`（工作流 data_enrich 工具与 `TopicService` 复用；★ 升级为 Serper→DeepSeek→Kimi 三级级联，Serper 命中即注入上下文供 DeepSeek 提炼）、★ `SerperSearchService`（Serper.dev 客户端，RestTemplate，/search 与 /news，失败静默降级返回 null；★ 新增 fetchQuote：从 Google 行情卡 answerBox / organic 摘要正则解析实时行情（现价/涨跌额/涨跌幅），数据准备闸门的行情首选通道）、`StockAnalysisService`、`StockDataService`；★ `StockDataAcquisitionService`（个股结构化数据自动拉取：行情 Serper 首选/东财降级，**日K线走 KimiKlineService（腾讯→新浪→Kimi）不再依赖东财**，财务/60日资金流仍走东财，工作流数据准备闸门，K线不足阻断启动快速失败）；★ `KimiKlineService`（日K线多源获取：腾讯 ifzq 直连为主→新浪直连次之→Kimi agent loop 兜底；Kimi 兜底声明受限 http_get 工具（域名白名单）由 Kimi 自选行情 URL 并整理为固定 CSV，全程不执行 LLM 生成代码）；★ 本轮新增 5 个服务（见 §4.2）。

### 4.1 本轮新增控制器（`com.stock.controller`）

| 控制器 | 根路径 | 职责 |
|---|---|---|
| `MarketDataController` | `/api/market` | 市场级数据只读查询：涨停池 / 龙虎榜 / 北向 / 两融 / 大宗交易 / 各类可用交易日（§7.1） |
| `MarketSentimentController` | `/api/market/sentiment` | 情绪周期仪：多日情绪分序列 + `/interpret` LLM 定性（当日缓存，失败降级规则法） |
| `WatchlistController` | `/api/watchlist` | 自选股 CRUD + 分组/标签；★ GET 列表内联行情速览（`WatchlistQuoteView`：latestPrice/changePct/quoteDate，quote 优先、日K退化、无数据置 null） |
| `TopicController` | `/api/topics` | 题材库：当日涨停池行业分布 + LLM 级联热点题材 |
| `StockHolderController` | `/api/stock/{code}` | 股东追踪：`/holders` 十大（流通）股东、`/holder-count` 股东户数趋势 |
| `StockComparisonController` | `/api/stock/{code}` | 财务对标：`/comparison` 指标并排、`/comparison/comment` LLM 点评（code + 当日缓存） |
| `InvestNoteController` | `/api/stock/{code}/notes` | 投资笔记 CRUD（5 分类，正文 1-10000 字校验） |
| `PriceAlertController` | `/api/stock/{code}` | 价格预警 CRUD（`/alerts`）+ `/signals` 技术信号实时计算（不入库） |
| `StockLhbController` | `/api/stock/{code}/lhb` | 个股龙虎榜（只读复用 `StockLhbDetailRepository`，不新建表） |

### 4.2 本轮新增服务（`com.stock.service`）

| 服务 | 职责 |
|---|---|
| `MarketSentimentService` | 情绪分（0-100）计算：涨停家数 30% + 连板高度 25% + 封板率/炸板率反向 25% + 两融余额环比 20%；北向不参与（数据停更），两融缺失时按剩余权重归一化；`buildSentimentContext(days)` 供 MARKET_REVIEW 工作流预注入；`interpret` LLM 定性按自然日缓存，失败降级规则法 |
| `TopicService` | 题材库：当日涨停池行业分布（上限 15 行业、每行业 5 代表股）作为上下文送 `DataEnricher` 级联挖掘热点；缓存复用 `enrichment_data`（key：stockCode=`MARKET` + directionKey=`TOPIC_yyyy-MM-dd`）6 小时；LLM 全失败降级为仅行业分布 |
| `StockComparisonService` | 财务对标：目标股与同行业对标股指标并排；LLM 点评按 `code` + 当日缓存（复用 `enrichment_data`），入库前过 `WorkflowTextUtils.stripNonBmp` 净化 emoji |
| `AlertScanService` | 价格预警扫描：`@Scheduled` 每 5 分钟（initialDelay 60s）对照 `StockQuote` 最新价，命中置 `triggered=true` 并记触发时间；依赖 `SchedulingConfig` 开启调度 |
| `TechnicalSignalService` | 技术信号实时计算（**不入库**）：基于最近 60 根日K 算 MA5/MA20 金叉死叉 + 放量（最新量 > 5 日均量×2）；K 线不足 21 根时 `available=false` |

工作流模块与股票分析模块并存于同一应用、共享同一 MySQL 数据源与 `AgentModelConfig` 模型配置，REST 前缀（`/api/v1/workflow*`）与异常处理（`WorkflowControllerAdvice` 限定本包）均相互隔离。★ 本轮两模块出现双向复用：工作流侧 `MarketZtTool` / `MarketLhbTool` / `WorkflowRunService` 读股票域市场数据表与 `MarketSentimentService`，`DailyScheduleService` 读 `WatchlistRepository`；股票分析侧 `MarketSentimentService` / `StockComparisonService` 复用 `WorkflowTextUtils.stripNonBmp` 净化契约。

## 5. 后端测试结构

**工作流模块**（`com.stock.workflow.*`，14 类 / 128 例）：

| 测试类 | 用例数 | 说明 |
|---|---|---|
| `workflow/engine/JsonToBpmnConverterTest` | 16 | 纯单测：校验规则（id 合法性/重复/环/孤立节点/agentId 引用）与 BPMN 结构（网关插入/异步标记/FieldExtension） |
| `workflow/service/WorkflowGeneratorServiceTest` | 15 | Mockito 单测：代码围栏剥离、三重校验、失败重试、回退模板、aiEdit 状态回退 |
| `workflow/engine/WorkflowEngineIntegrationTest` | 6 | 独立 Flowable H2 内存引擎集成测试：串行/并行执行顺序、失败进死信、取消，不依赖 Spring/MySQL/LLM |
| `workflow/service/WorkflowSeedServiceTest` | 14 | ★ 种子版本化 upsert：不存在创建/低版原地升级/版本到位跳过/seedVersion=null 同名打标、用户数据不被触碰、PUBLISHED 重部署 |
| `workflow/engine/WorkflowRunServiceStartTest` | 9 | ★ 启动预注入：STOCK_ANALYSIS 五上下文与 MARKET_REVIEW 情绪上下文注入/降级不中断；★ 数据闸门不通过（K线拉不到）阻断启动 |
| `workflow/engine/tools/StockToolsParamTest` | 10 | ★ 股票类工具入参宽松解析（代码 6 位校验、days 夹取与缺省） |
| `workflow/engine/tools/MarketToolsParamTest` | 13 | ★ market_zt / market_lhb 参数与日期缺省、poolType 归一、无数据文案 |
| `workflow/repository/ReportRunLogRepositoryTest` | 7 | ★ 报告库只读聚合查询（原生 SQL SUBSTRING 预览投影、分页与筛选） |
| `workflow/service/ReportLibraryServiceTest` / `ReportExportServiceTest` | 10 / 5 | ★ 报告检索组装与 Markdown/HTML 导出（commonmark 渲染 + escapeHtml） |
| `workflow/engine/StockContextPreloaderTest` | 9 | 预注入上下文构建与单类缺数据兜底 |
| `workflow/engine/WorkflowTextUtilsTest` | 4 | stripNonBmp emoji 净化与输出截断阈值 |
| `workflow/service/DailyScheduleServiceTest` | 7 | ★ 盘后批处理：自选股为空/DRAFT 跳过、两股串行触发与终态计数、等终态超时 TIMEOUT、triggerRun 防重入 |
| `workflow/controller/ScheduleControllerTest` | 3 | ★ standalone MockMvc：/run-now 受理/拒重入与 /status 响应结构 |

**股票分析模块**（`com.stock.*`，★ 本轮新增 11 类 / 97 例）：

| 测试类 | 用例数 | 说明 |
|---|---|---|
| `controller/MarketDataControllerTest` | 14 | 市场数据只读端点（涨停池/龙虎榜/两融/北向/大宗交易 + dates）参数与空数据语义 |
| `controller/MarketDataRealDbVerifyTest` | 6 | 真实 MySQL 数据可用性校验（行数/日期边界/单位归一） |
| `controller/WatchlistControllerTest` | 14 | 自选股 CRUD 与重复添加、不存在删除；★ 行情速览内联（quote 优先/日K退化/无数据置 null/异常不阻断） |
| `controller/InvestNoteControllerTest` | 14 | 投资笔记 CRUD、标签与分页 |
| `repository/MarketDataRepositoryTest` | 7 | 市场表派生查询与 findMaxTradeDate |
| `service/MarketSentimentServiceTest` | 13 | 情绪评分四项加权、周期定性、天数上限与数据断档降级 |
| `service/StockComparisonServiceTest` | 9 | 对标指标计算与 LLM 点评（含 stripNonBmp 入库净化） |
| `service/TechnicalSignalServiceTest` | 8 | MA 金叉死叉与放量信号实时计算 |
| `service/AlertScanServiceTest` | 7 | 价格预警命中/不命中与触发后不重复告警 |
| `service/TopicServiceTest` | 3 | ★ 题材库：行业 Top15/代表股上限、buildContext 格式、enrich key 契约（MARKET+TOPIC_日期）与 LLM 失败 fallback |
| `service/SerperQuoteParseTest` | 5 | ★ 行情文本解析：Google 行情卡/雪球/Yahoo 千分位/负涨跌/非行情片段（今开换手）不误判 |
| `service/KimiKlineParseTest` | 4 | ★ Kimi 兜底 CSV 解析（容忍代码块/表头）+ http_get 域名白名单（含后缀伪装拦截） |
| `service/KimiKlineServiceIT` | 2 | ★ 腾讯/新浪 K线真实联通性：`*IT` 后缀 + `@EnabledIfSystemProperty(kimi-kline.it=true)` 默认不跑，手动 `-Dtest=KimiKlineServiceIT -Dkimi-kline.it=true` |
| `service/SerperSearchServiceIT` | 2 | ★ Serper.dev 真实联通性测试：`*IT` 后缀**不在 surefire 默认 includes** 且 `@EnabledIfSystemProperty(serper.it=true)`，`mvn test` 默认不跑，需手动 `-Dtest=SerperSearchServiceIT -Dserper.it=true` |

共 **28 个测试类、236 个 `@Test`**；最近全量 `mvn test` 0 失败（surefire：229 例实跑 + `MarketDataRealDbVerifyTest` 6 例 `@EnabledIfSystemProperty(market.realdb)` 缺省跳过；`SerperSearchServiceIT` / `KimiKlineServiceIT` 各 2 例默认不跑）；集成测试类名以 `*IntegrationTest` 结尾以命中 surefire 默认 includes。

## 6. 数据表清单

### 6.1 业务表（JPA `ddl-auto: update` 自动维护）

**workflow_def —— 工作流定义**

| 列 | 类型/约束 | 说明 |
|---|---|---|
| id | BIGINT PK 自增 | 主键 |
| name | VARCHAR(100) NOT NULL | 工作流名称（业务上唯一，Controller 查重） |
| description | VARCHAR(500) | 描述 |
| category | VARCHAR(30) | DEV_PROCESS / STOCK_ANALYSIS / CUSTOM / ★ MARKET_REVIEW（触发 `_sentiment_context` 预注入） |
| definition_json | LONGTEXT | 简化工作流 JSON 定义（前端画布/AI 生成产物） |
| process_definition_key | VARCHAR(100) | Flowable 流程 key（`wf_{id}`，发布时回写） |
| deployment_id | VARCHAR(64) | Flowable 部署 id（发布时回写） |
| status | VARCHAR(20) | DRAFT / PUBLISHED / ARCHIVED |
| version | INT | 发布版本号（每次发布 +1） |
| created_by | VARCHAR(20) | USER / AGENT（AI 生成为 AGENT） |
| seed_version | INT | ★ 种子版本号：null=用户创建；非空=由种子机制管理，低于目标版本时启动原地升级（见 DESIGN.md §5.7） |
| created_at / updated_at | DATETIME | JPA Auditing 自动维护 |

**workflow_agent_def —— 智能体定义**

| 列 | 类型/约束 | 说明 |
|---|---|---|
| id | BIGINT PK 自增 | 主键 |
| name | VARCHAR(100) NOT NULL | 智能体名称（业务上唯一） |
| type | VARCHAR(20) | PROMPT / AGENTSCOPE |
| system_prompt | LONGTEXT | 系统提示词 |
| model_choice | VARCHAR(30) | deepseek-v4 / deepseek-v5 / kimi |
| temperature | DOUBLE | 采样温度 |
| max_tokens | INT | 最大输出 token（缺省 2560） |
| tools_json | VARCHAR(2000) | AGENTSCOPE 型工具 JSON 数组：内置工具组（filesystem/shell/memory）+ 自定义工具（stock_kline/stock_finance/stock_fundflow/stock_industry/data_enrich ★ + market_zt/market_lhb/web_search），★ 共 11 项白名单 |
| max_iterations | INT | AGENTSCOPE 型迭代上限（映射 HarnessAgent maxIters） |
| loop_depth | INT | 循环嵌套深度约束（注入 sysPrompt 的提示词级约束） |
| events_json | VARCHAR(2000) | AGENTSCOPE 型事件订阅过滤配置：JSON 字符串数组（事件类型名），非空时按订阅集合过滤 streamEvents() 事件转发，空/null 保持默认行为（向后兼容） |
| seed_version | INT | ★ 种子版本号：null=用户创建；非空=由种子机制管理，低于目标版本时启动原地升级 |
| created_at / updated_at | DATETIME | JPA Auditing 自动维护 |

**workflow_run_log —— 节点执行日志**（索引 `idx_wf_run_log_proc_inst(process_instance_id)`）

| 列 | 类型/约束 | 说明 |
|---|---|---|
| id | BIGINT PK 自增 | 主键 |
| workflow_def_id | BIGINT | 所属工作流定义 id |
| process_instance_id | VARCHAR(64) | Flowable 流程实例 id |
| node_id / node_name | VARCHAR(100) | 简化 JSON 节点 id 与名称 |
| agent_id | BIGINT | 执行该节点的 AgentDef id |
| status | VARCHAR(20) | RUNNING / COMPLETED / FAILED |
| input_text | LONGTEXT | 占位符插值后的完整输入 |
| output_text | LONGTEXT | 节点输出全文 |
| error_message | VARCHAR(2000) | 失败原因（截断至 2000 字符） |
| started_at / completed_at | DATETIME | 开始/结束时间 |

**stock_fund_flow —— 个股每日资金流**（★ 本轮新增，属股票分析域 `com.stock.entity.StockFundFlow`；唯一索引 `idx_fundflow_code_date_unique(code, tradeDate)`）

| 列 | 类型/约束 | 说明 |
|---|---|---|
| id | BIGINT PK 自增 | 主键 |
| code | VARCHAR(6) NOT NULL | 6 位股票代码 |
| trade_date | DATE NOT NULL | 交易日 |
| main_net_inflow / main_net_ratio | DECIMAL | 主力净流入(元，=超大单+大单) / 占成交额比例(%) |
| super_large / large / medium / small_net_inflow | DECIMAL | 超大单/大单/中单/小单净流入(元) |
| update_time | DATETIME NOT NULL | 数据更新时间 |

消费方：工作流预注入 `_fundflow_context`（近 20 日）与 stock_fundflow 工具（最多 60 日）；表可能为空，消费方无数据时返回“（暂无该类数据）”占位，禁止 Mock。

#### ★ 本轮新增 10 张表（股票分析域 `com.stock.entity`，均 `ddl-auto: update` 自动维护）

**一、市场数据管道 5 张（T19）**——入库通道为 `data-fetcher/fetch_market.py`（AKShare → MySQL 直写，T+1 盘后）；索引口径参照 `StockKlineDaily`（code + trade_date 复合）：

| 表名 / 实体 | 关键列 | 索引 | 说明 |
|---|---|---|---|
| `stock_zt_pool` / `StockZtPool` | code, name, trade_date, pool_type, close_price, change_pct, limit_up_days, first_time, last_time, open_times, amount, turnover_rate, industry, reason, zt_stat, update_time | `idx_ztpool_code_date(code, tradeDate DESC)`、`idx_ztpool_date_type(tradeDate, poolType)`、`idx_ztpool_code_date_type_unique(code, tradeDate, poolType)` UNIQUE | 涨停股池；`pool_type` = TODAY（今日池）/ PREVIOUS（昨日池）；`zt_stat` 如 `7/7`（七天七板）。⚠ `reason` 恒为 null，连板属性一律用 `zt_stat`/`limit_up_days` |
| `stock_lhb_detail` / `StockLhbDetail` | code, name, trade_date, rank_reason, buy_amount, sell_amount, net_amount, total_amount, change_pct, close_price, interpretation, update_time | `idx_lhb_code_date(code, tradeDate DESC)`、`idx_lhb_date(tradeDate)`（**无唯一索引**） | 龙虎榜明细；同股同日可多行（多个上榜原因），抓取端按业务键先查差集再插 |
| `stock_north_flow` / `StockNorthFlow` | trade_date, net_flow, accum_flow, buy_amount, sell_amount, update_time | `idx_northflow_date_unique(tradeDate)` UNIQUE | 北向资金（单位亿元）。⚠ **数据止于 2024-08-16**（交易所停止披露），情绪计算已降级不依赖北向 |
| `stock_margin_daily` / `StockMarginDaily` | trade_date, market, financing_balance, financing_buy_amount, securities_balance, total_balance, update_time | `idx_margin_date_market_unique(tradeDate, market)` UNIQUE、`idx_margin_date(tradeDate DESC)` | 两融汇总（按市场 SH/SZ 分行）。⚠ 深交所接口原单位为**亿元**，入库前已 ×1e8 归一为**元** |
| `stock_block_trade` / `StockBlockTrade` | code, name, trade_date, price, close_price, volume, amount, premium_rate, buyer_branch, seller_branch, update_time | `idx_blocktrade_code_date(code, tradeDate DESC)`、`idx_blocktrade_date(tradeDate)`（**无唯一索引**） | 大宗交易明细；同股同日可多行（多笔成交） |

**二、自选股（T20）**

| 表名 / 实体 | 关键列 | 说明 |
|---|---|---|
| `watchlist` / `Watchlist` | stock_code, stock_name, group_name（缺省“默认分组”）, tags（逗号分隔）, note, created_at | 自选股；CRUD 由 `WatchlistController` 提供，`DailyScheduleService` 盘后批处理按本表逐只串行分析 |

**三、个股深度增强 4 张（T23）**

| 表名 / 实体 | 关键列 | 索引 | 说明 |
|---|---|---|---|
| `stock_holder_top` / `StockHolderTop` | code, report_date, holder_type, holder_rank, holder_name, holder_nature, shares, hold_ratio, change_desc, change_ratio, update_time | `idx_holdertop_code_date(code, reportDate DESC)`、`idx_holdertop_unique(code, reportDate, holderType, holderRank)` UNIQUE | 十大股东 / 十大流通股东（`holder_type` 区分）；入库通道 `data-fetcher/fetch_holders.py` |
| `stock_holder_count` / `StockHolderCount` | code, stat_date, holder_count, prev_count, change_ratio, avg_hold_shares, avg_hold_value, update_time | `idx_holdercnt_code_date(code, statDate DESC)`、`idx_holdercnt_unique(code, statDate)` UNIQUE | 股东户数及环比变化趋势 |
| `invest_note` / `InvestNote` | code, category, content, created_at, updated_at | `idx_note_code_time(code, updatedAt DESC)` | 投资笔记；`category` 5 分类枚举 INVEST_LOGIC / RISK_POINT / BUY_CONDITION / SELL_CONDITION / FREE_NOTE；`content` 1-10000 字校验 |
| `price_alert` / `PriceAlert` | code, type, threshold, enabled, triggered, triggered_at, created_at | `idx_alert_code(code)`、`idx_alert_scan(enabled, triggered)` | 价格预警；`type` 枚举 PRICE_ABOVE / PRICE_BELOW；`AlertScanService` 每 5 分钟按 `idx_alert_scan` 扫描未触发且启用的规则 |

> 技术信号（MA5/MA20 金叉死叉、放量）**不建表**，由 `TechnicalSignalService` 基于 `stock_kline_daily` 实时计算；报告库也**不建新表**，只读复用 `workflow_run_log`。

### 6.2 Flowable ACT_* 表（引擎自建自管，`flowable.database-schema-update: true`）

Flowable 7.2.0 启动时在同一 MySQL 库中自动创建/升级引擎表，本项目**不自建任何执行态业务表**，执行态与历史直接复用引擎表：

> ⚠️ **运维警示**：ACT_* 表由 Flowable 引擎自动管理，**严禁手工清理或删除**（包括 TRUNCATE / DELETE / DROP），否则会破坏运行中/历史流程实例数据（Job 队列、流程变量、历史活动）。

| 表前缀 | 用途 | 本项目的使用方式 |
|---|---|---|
| `ACT_RE_*` | 仓库（Repository）：部署与流程定义 | `WorkflowDeployService` 部署 BpmnModel 后由引擎写入 |
| `ACT_RU_*` | 运行时（Runtime）：执行、变量、异步 Job/死信 Job | 节点输出以 `{nodeId}_output` 流程变量存于此；`WorkflowRunService` 用死信 Job 数判定 FAILED |
| `ACT_HI_*` | 历史（History）：流程实例、活动实例、变量历史 | 状态合成视图与 `/history/{workflowDefId}` 历史列表基于 `HistoryService` 查询 |
| `ACT_GE_*` | 通用（General）：资源与属性 | 部署的 `wf_{id}.bpmn20.xml` 资源存于此 |
| `ACT_ID_*` | 身份（Identity） | 未使用（无引擎级用户体系） |

## 7. ★ 本轮新增 API 端点清单

> 详细请求/响应语义与设计理由见 `DESIGN.md` §6.4~§6.9。本节只作结构性盘点。

### 7.1 市场数据与市场中心（T19/T20）

| 方法 | 端点 | 参数 | 说明 |
|---|---|---|---|
| GET | `/api/market/zt-pool` | `date`（yyyy-MM-dd，缺省最近可用日）、`poolType`（TODAY\|PREVIOUS，缺省 TODAY） | 涨停股池明细与统计 |
| GET | `/api/market/lhb` | `date` | 当日龙虎榜（净买额降序） |
| GET | `/api/market/north-flow` | `days`（缺省 30） | 北向资金序列（⚠ 数据止于 2024-08-16） |
| GET | `/api/market/margin` | `days`（缺省 30） | 两融余额序列（按市场） |
| GET | `/api/market/block-trade` | `date` | 当日大宗交易明细 |
| GET | `/api/market/dates` | `type`（zt\|lhb\|block\|margin，缺省 zt） | 该类数据已入库的可用交易日列表（前端日期选择器数据源） |
| GET | `/api/market/sentiment` | `days`（缺省 10） | 情绪分序列与分项指标 |
| GET | `/api/market/sentiment/interpret` | — | LLM 周期定性（冰点/启动/主升/退潮），当日缓存，失败降级规则法 |
| GET | `/api/topics` | — | 题材库：行业分布 + LLM 热点题材 |
| GET | `/api/watchlist` | — | 自选股列表 |
| POST | `/api/watchlist` | body `Watchlist` | 新增自选股 |
| PUT | `/api/watchlist/{id}` | body 部分字段 | 修改（分组/标签/备注） |
| DELETE | `/api/watchlist/{id}` | — | 删除 |

### 7.2 报告库与定时任务（T21/T22）

| 方法 | 端点 | 参数 | 说明 |
|---|---|---|---|
| GET | `/api/reports` | `stockCode?`、`workflowName?`、起止时间、`page`（缺省 0）、`size`（缺省 20） | 报告分页检索（运行粒度，预览 200 字符） |
| GET | `/api/reports/{runId}` | — | 单次运行节点明细（`ReportRunDetail`） |
| GET | `/api/reports/{runId}/export` | `format`（md\|html，缺省 md） | 导出报告文件（`ResponseEntity<byte[]>`） |
| POST | `/api/schedule/run-now` | — | 手动触发盘后批处理（已在跑时拒绝重入） |
| GET | `/api/schedule/status` | — | 最近一次批处理执行摘要 |

### 7.3 个股深度增强（T23，均在 `/api/stock/{code}` 下）

| 方法 | 端点 | 参数 | 说明 |
|---|---|---|---|
| GET | `/api/stock/{code}/holders` | — | 十大股东 / 十大流通股东（按报告期） |
| GET | `/api/stock/{code}/holder-count` | `periods`（缺省值见 `DEFAULT_PERIODS`） | 股东户数趋势 |
| GET | `/api/stock/{code}/comparison` | — | 财务对标指标并排 |
| GET | `/api/stock/{code}/comparison/comment` | — | LLM 对标点评（code + 当日缓存，入库前 stripNonBmp） |
| GET | `/api/stock/{code}/notes` | `category?` | 投资笔记列表（可按分类筛选） |
| POST / PUT / DELETE | `/api/stock/{code}/notes`、`/notes/{id}` | body `InvestNote` | 笔记新增/修改/删除（1-10000 字校验） |
| GET | `/api/stock/{code}/alerts` | — | 价格预警列表 |
| POST / PUT / DELETE | `/api/stock/{code}/alerts`、`/alerts/{id}` | body `PriceAlert` | 预警新增/修改/删除 |
| GET | `/api/stock/{code}/signals` | — | MA5/MA20 金叉死叉 + 放量信号（实时计算，不入库） |
| GET | `/api/stock/{code}/lhb` | — | 个股龙虎榜上榜记录 |

### 7.4 工作流智能体层变更（T22 及后续）

| 方法 | 端点 | 变更 |
|---|---|---|
| GET | `/api/v1/workflow-agent/available-tools` | 可用工具由 8 项增至 **11 项**（内置 3 + 自定义工具 5→8：新增 `market_zt` / `market_lhb` / `web_search` 及其参数说明） |
| POST | `/api/v1/workflow/{id}/execute` | `MARKET_REVIEW` 类工作流启动时自动预注入 `_sentiment_context`（近 10 日情绪指标序列） |
| GET | `/api/v1/workflow`／`/api/v1/workflow-agent` | 种子数据由 33 智能体 + 6 工作流扩至 **43 智能体 + 7 工作流**（★ 新增「功能开发协作工作流」v1：`DEV_PROCESS`、14 节点、3 处并行汇聚、入口变量 `${requirement}`，新增 10 个协作域智能体并复用开发工程师/测试工程师/代码评审员，见 DESIGN.md §5.7.2） |

