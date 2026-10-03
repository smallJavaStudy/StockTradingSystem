# Agent 个人AI平台 — 开发计划

> **定位**：替代手机上所有智能 APP（豆包/Kimi 等）的个人 AI 操作系统。
> **第一插件**：股票分析（对接 StockTradingSystem）。
> **远期目标**：形成可复制的方法论，让家人朋友也能搭建适合自己的智能体。
> **核心差异**：不是"一个更好的豆包"，而是"一个了解你的 AI 操作系统"。

---

## 一、豆包为什么不够好

复盘"跟豆包的对话记录.md"，四轮对话的核心问题：

| 轮次 | 用户问 | 豆包给 | 缺失 |
|------|--------|--------|------|
| Q1 | 介绍两家公司 | 公司对比 | 赛道全景、技术面、产业链地位 |
| Q2 | 其他A股？涨价谁受益？ | 涨价受益排序 | 技术面验证、产业链传导逻辑 |
| Q3 | 筹码结构、走势预判 | 技术面分析 | 与基本面交叉验证 |
| Q4 | 产业链位置、硅片 | 产业链定位 | 前面积累需手动关联 |

**豆包的架构本质：单Agent + 单轮搜索 + 被动响应。** 每次只响应当前问题，不主动扩展维度，不预设分析框架。用户必须自己当"调度器"，把一个大问题拆成多个小问题分别提问，再把答案拼成完整画像。

**超越的关键不是"回答得更详细"，而是"一轮把该覆盖的维度全部覆盖"。**

---

## 二、超越目标

### 2.1 体验对比

| 场景 | 豆包 | 超级分析师 |
|------|------|-----------|
| "分析下扬杰科技" | 给公司基本面 | 基本面 + 赛道位置 + 技术面 + 产业链定位，一次性全量输出 |
| "功率半导体涨价" | 给受益排序 | 锁定涨价趋势 + 受益梯队 + 技术面验证 + 产业链传导 + 风险矩阵 |
| 多标的对比 | 用户自己来回问 | 自动识别可对比标的，主动生成多维度对比表 |
| 后续追问 | 从零开始理解上下文 | 继承全量分析上下文，精准补充 |

### 2.2 技术指标

- **单轮覆盖维度**：≥4 个分析维度（基本面/赛道/技术面/产业链），豆包通常 1-2 个
- **主动扩展**：识别用户意图后，自动补全关联维度，不等用户追问
- **交叉验证**：基本面结论与技术面信号相互印证，发现矛盾时标红
- **结构化输出**：表格、梯队、排序、评分——不止是自然语言叙述
- **可操作判断**：给出具体的关注区间、止损参考、催化事件时间线

---

## 三、Agent 数量全景：你需要搭建多少个智能体？

### 3.1 概念定义

| 角色 | 定义 | 类比 |
|------|------|------|
| **专家团智能体 (Orchestrator)** | 协调多个专家智能体，负责拆解任务、分配子任务、汇总结果、交叉验证 | 基金经理：自己不写研报，但分配研究方向给研究员，最后汇总成投资决策 |
| **专家智能体 (Specialist)** | 专注于单一分析领域，有独立的 System Prompt、工具集和输出模板 | 研究员：只研究一个方向，产出结构化报告 |

### 3.2 总体数量

#### Agent 平台自身（需新建）

```
Agent 平台
├── 专家团智能体 × 4
│   ├── MasterOrchestrator         总入口调度（意图识别 → 插件路由）
│   ├── GeneralChatOrchestrator    通用对话调度（替代豆包/Kimi）
│   ├── CognitionOrchestrator      认知分析调度（用户画像）
│   └── FamilyDeployOrchestrator   亲友部署调度（远期）
│
└── 专家智能体 × 12（新建）
    ├── 通用对话插件 (3个)
    │   ├── WebSearchAgent          多源并行搜索
    │   ├── ContentAgent            内容生成/写作/翻译
    │   └── ReasoningAgent          复杂推理/多步规划
    │
    ├── 认知分析插件 (5个)
    │   ├── QuestionAnalyzerAgent   提问意图/有效性/出发点分析
    │   ├── BlindSpotDetectorAgent  知识盲区识别（你缺什么维度）
    │   ├── CognitiveReportAgent    周期性认知报告生成
    │   ├── PreferenceModelerAgent  偏好建模（你喜欢什么风格的回答）
    │   └── KnowledgeGapAgent       知识缺口补充建议
    │
    ├── 平台服务 (3个)
    │   ├── ProfileInitializerAgent 新用户画像初始化（亲友部署用）
    │   ├── MethodologyAgent        搭建方法论引导
    │   └── SystemHealthAgent       系统自检/诊断
    │
    └── 领域适配 (1个)
        └── StockPluginAgent        适配 StockTradingSystem REST API
```

#### StockTradingSystem（已有）

```
StockTradingSystem
├── 专家团智能体 × 1                AgentOrchestrationService（Phase1并发 + Phase2综合）
│
└── 专家智能体 × 11（已有）
    ├── TECHNICAL_ANALYSIS          日K线技术分析
    ├── TREND_ANALYSIS              近期走势研判
    ├── SECTOR_ANALYSIS             所属板块分析
    ├── CAPITAL_FLOW                资金面分析
    ├── SENTIMENT_ANALYSIS          市场舆情分析
    ├── FUNDAMENTAL_ANALYSIS        基本面分析
    ├── VALUATION_ANALYSIS          估值分析
    ├── CHIP_STRUCTURE              筹码结构分析
    ├── RISK_WARNING                风险预警
    ├── COMPREHENSIVE_ADVICE        综合投资建议
    └── RESOLVER                    股票名称解析
```

### 3.3 汇总

| 分类 | 专家团 | 专家 | 状态 |
|------|--------|------|------|
| StockTradingSystem | 1 | 11 | ✅ 已建成 |
| Agent 平台 (近期) | 3 | 9 | 🔨 待建 |
| Agent 平台 (远期) | 1 | 3 | 🔮 远期 |
| **合计** | **5** | **23** | — |

> **关键认知**：专家团智能体是"决策层"，决定"做什么"；专家智能体是"执行层"，负责"怎么做"。你可以把专家团理解为小组长，专家理解为组员。组员的 System Prompt 高度专一化，组长的 Prompt 侧重编排和判断。

---

## 四、Agent 平台架构

### 4.1 两层项目关系

```
D:\code\Agent\                              ← 个人AI平台（新建）
│
│  ┌─────────────────────────────────────┐
│  │ 认知引擎 (Cognition)                 │
│  │ 用户画像 / 盲区检测 / 偏好建模       │
│  │ 提问分析 / 习惯学习 / 认知报告       │
│  └─────────────────────────────────────┘
│                    │
│  ┌─────────────────┼───────────────────┐
│  │ 通用对话插件    │ 股票分析插件      │  ...更多插件
│  │ (替代豆包)     │ (对接ST系统)      │
│  └────────────────┼───────────────────┘
│                    │
│        REST API 调用
│                    │
D:\code\StockTradingSystem\   ↓
  ┌──────────────────────────────────────┐
  │ AgentOrchestrationService            │
  │  ┌────┐ ┌────┐ ┌────┐ ... ┌────┐    │
  │  │技术│ │趋势│ │板块│     │风险│    │
  │  │分析│ │研判│ │分析│     │预警│    │
  │  └────┘ └────┘ └────┘ ... └────┘    │
  │  9个方向并发 → 综合投资建议           │
  └──────────────────────────────────────┘

其中 D:\code\StockTradingSystem\agent-scope\ 是原型与文档仓库（学习笔记、开发计划），
不属于生产代码。

### 4.2 一个请求的流转路径

```
用户: "分析下扬杰科技"
  │
  ▼
MasterOrchestrator (专家团)
  ├─ 意图识别: stock_analysis, target=扬杰科技
  ├─ 提取上下文: 当前用户画像（偏好深度基本面分析、回避纯技术指标）
  └─ 路由到: StockPluginAgent
        │
        ▼
      StockPluginAgent (专家)
        ├─ 调用 StockTradingSystem REST API: POST /api/v1/analysis
        ├─ 参数: code=300373, directions=[全部9个方向]
        └─ 接收 SSE 流式结果
              │
              ▼
      AgentOrchestrationService (ST系统内的专家团)
        ├─ Phase 1: 9个方向并发分析（每个都是一个 HarnessAgent）
        ├─ Phase 2: 综合投资建议
        └─ SSE 推流: THINKING_DELTA + TEXT_DELTA
              │
              ▼
      结果返回给用户，同时触发:
        │
        ▼
      CognitionOrchestrator (专家团，后台异步)
        ├─ QuestionAnalyzerAgent: "用户这次问的是个股深度分析..."
        ├─ BlindSpotDetectorAgent: "用户未涉及估值和产业链维度..."
        └─ PreferenceModelerAgent: "用户偏好结构化表格输出..."
              │
              ▼
          更新用户画像 DB
```

### 4.3 为什么不是一个大 Agent 包办所有

豆包/Kimi 的通病：一个模型 + 一个 System Prompt 处理所有任务。结果是：
- 股票分析不够专业（没有专用的分析框架）
- 用户画像没有积累（每次对话都是新的）
- 答案风格不稳定（同一个问题不同时间回答质量波动大）

多 Agent 架构的核心优势：
- **每个专家 Agent 只做一件事**，System Prompt 高度专一化，输出质量远超通用 Agent
- **专家团 Agent 只做编排**，不亲自分析，避免"裁判兼运动员"的 bias
- **认知 Agent 只观察不干涉**，静默学习，不影响对话体验

---

## 五、插件设计

### 5.1 插件1：股票分析（对接 StockTradingSystem）

**状态：核心分析引擎已完成。** 详见 [AnalysisDirection.java](file:///d:/code/StockTradingSystem/backend/src/main/java/com/stock/agent/AnalysisDirection.java) — 10 个分析方向的 System Prompt 和输出模板均已定义，[AgentOrchestrationService.java](file:///d:/code/StockTradingSystem/backend/src/main/java/com/stock/service/AgentOrchestrationService.java) — 9 方向并发分析 + 综合投资建议的编排引擎已实现。

Agent 平台只需1个 **StockPluginAgent**（专家），负责：
1. 接收 MasterOrchestrator 路由的股票分析请求
2. 组装 REST 调用参数（stockCode + directions + modelChoice）
3. 调用 `POST /api/v1/analysis` 并转发 SSE 流
4. 将结果上下文传递给 CognitionOrchestrator

> StockTradingSystem 内部已有的 10 个分析 Agent 详见原 dev-plan §3.3 的分析框架，此处不再展开。

### 5.2 插件2：通用对话（替代豆包/Kimi）

**专家团：GeneralChatOrchestrator**

```
System Prompt 核心:
"你是用户的个人AI助手。你的回答风格应适应用户的偏好画像。
当前用户偏好: {userProfile.preferences}
当前用户知识盲区: {userProfile.blindSpots}
对话历史显示用户提问习惯: {userProfile.habitSummary}

在回答时:
- 如果用户的问题涉及已知盲区，主动提供更详细的解释
- 偏好简洁直接则避免冗长铺垫
- 涉及实时信息时调用搜索工具"
```

**专家智能体 (3个):**

| Agent | 职责 | System Prompt 要点 | 工具 |
|-------|------|--------------------|------|
| **WebSearchAgent** | 多源并行搜索 | "同时查询多个搜索引擎，合并去重后按相关性排序。每条结果标注来源和时效性。" | Bing API, SerpAPI |
| **ContentAgent** | 内容生成/写作 | "根据用户画像调整语气和深度。技术型用户给细节，决策型用户给结论。" | 无（纯LLM能力） |
| **ReasoningAgent** | 复杂推理/多步规划 | "遇到需要多步推理的问题，先列出推理链，再逐级推导。涉及数据时标注数据来源和可信度。" | 计算工具 |

### 5.3 插件3：认知分析（用户画像）

**这是整个 Agent 平台最核心的差异化能力。** 通用对话和股票分析是"功能"，认知分析是"灵魂"。

**专家团：CognitionOrchestrator**

```
工作模式: 后台异步，不阻塞用户对话
触发时机: 每次对话结束后

System Prompt 核心:
"你是用户的认知镜像分析师。你的任务不是回答问题，而是分析用户——
从每次对话中提取用户特征：
1. 提问习惯（直问/铺垫/试探）
2. 知识盲区（哪些领域反复回避/描述不准确）
3. 思维偏好（喜欢结构化还是叙事化、先结论还是先分析）
4. 价值取向（关注什么维度、忽略什么维度）

你的输出直接写入 UserProfile，影响后续所有其他 Agent 的行为。"
```

**专家智能体 (5个):**

| Agent | 职责 | 输入 | 输出 |
|-------|------|------|------|
| **QuestionAnalyzerAgent** | 分析单次提问的意图、有效性、准确性 | 用户原始问题 + 上下文 | `{intent, validity_score, accuracy_issues[], starting_point}` |
| **BlindSpotDetectorAgent** | 识别用户反复回避或描述不清晰的领域 | 用户历史问题集合 | `[{domain, confidence, evidence, last_seen}]` |
| **CognitiveReportAgent** | 每周生成用户认知报告 | 本周所有对话分析结果 | 自然语言报告（知识盲区变化、提问质量趋势、建议关注领域） |
| **PreferenceModelerAgent** | 建模用户对回答风格的偏好 | 用户对每次回答的反馈（追问/跳过/认可） | `{style: "简洁/详细", depth: "浅/深", format: "表格/叙述", tone: "正式/轻松"}` |
| **KnowledgeGapAgent** | 为已识别的盲区生成补充建议 | BlindSpotDetector 输出 | "您在过去2周反复追问估值相关问题，但在原始提问中从未主动涉及估值维度。建议关注PE/PB/PS的基础概念。" |

### 5.4 插件4：亲友部署（远期）

**专家团：FamilyDeployOrchestrator**

当方法论成熟后，这个调度器引导新用户（家人朋友）完成：

```
第一步: 回答"性格测试问卷"（ProfileInitializerAgent）
  → 生成种子用户画像
第二步: 选择需要的插件（MethodologyAgent）
  → "您主要想用AI做什么？ A. 日常聊天 B. 学习辅导 C. 工作助手 D. 投资分析"
第三步: 初始化 Agent 实例
  → 复制核心框架，注入该用户的画像数据
第四步: 首次使用引导
  → "试着问我一个问题，我会边回答边学习你的习惯"
```

**专家智能体 (3个，远期):**

| Agent | 职责 |
|-------|------|
| **ProfileInitializerAgent** | 通过问卷+初始对话建立用户种子画像 |
| **MethodologyAgent** | 引导用户理解 Agent 能做什么、怎么用 |
| **SystemHealthAgent** | Agent 实例自检（API连通性、画像健康度、Token消耗） |

---

## 六、超越豆包的关键设计

### 6.1 预设分析框架（不让LLM即兴发挥）

豆包的问题是每次都要"现场思考框架"。我们已将 10 个分析方向固化为精确的 System Prompt 模板（见 [AnalysisDirection.java](file:///d:/code/StockTradingSystem/backend/src/main/java/com/stock/agent/AnalysisDirection.java)），Agent 上来就知道要输出什么，省下的 Token 用于深度分析。

### 6.2 交叉验证（基本面 vs 技术面 vs 资金面）

这是豆包做不到的——多个 Agent 的分析结果放到一起，Orchestrator 做交叉验证：

```
基本面Agent: "扬杰科技IDM龙头，涨价直接受益，利润弹性大"
资金面Agent: "机构持仓稳定，北向资金持续增持"
技术面Agent: "沿20日线趋势上行"
          ↓ 交叉验证
结论: ✅ 基本面、资金面、技术面三线一致，信号可信度高

反面例子:
基本面Agent: "公司前景看好，SiC量产后营收翻倍"
资金面Agent: "近5日主力净流出12亿"
技术面Agent: "高位放量下跌，MACD死叉"
          ↓ 交叉验证  
结论: ⚠️ 基本面利好但资金在撤退、技术面走坏，需警惕"利好出尽"
```

### 6.3 主动追问（不等用户开口）

```
用户: "分析下扬杰科技"
Agent:
  → 9个专业Agent并发分析
  → 汇总后主动提示:
    "已完成9维度分析。另外注意到:
     1. 扬杰科技与华润微在SiC赛道存在直接竞争，需要对比吗？
     2. 功率半导体7月1日刚启动第二轮涨价，需要受益排名吗？
     3. 技术面显示今日缩量回踩5日线，需要详细筹码分析吗？"
```

### 6.4 量化评分体系

豆包的分析是纯定性的。我们加量化评分：

```
扬杰科技综合评分: 8.2/10
├─ 技术面: 7.5  (趋势向上但短期高位)
├─ 趋势:   8.0  (中期上升通道)
├─ 板块:   9.0  (涨价第一梯队受益者)
├─ 资金:   8.0  (主力稳健，北向持续流入)
├─ 舆情:   7.5  (正面但缺乏新催化)
├─ 基本面: 8.5  (IDM龙头 + SiC量产 + 财务稳健)
├─ 估值:   7.0  (不便宜，但景气周期可消化)
├─ 筹码:   8.0  (底部筹码锁定良好)
└─ 风险:   8.0  (重资产折旧压力 + 海外地缘风险可控)
```

---

## 七、工具生态

### 7.1 搜索工具（解决"豆包搜索结果有限"的问题）

```java
public class SearchTools {
    @Tool(name = "multi_search", description = "多源并行搜索，合并去重",
          readOnly = true, concurrencySafe = true)
    public SearchResult multiSearch(
        @ToolParam(name = "queries", description = "搜索关键词列表")
        List<String> queries,
        @ToolParam(name = "max_per_query", description = "每个query最大结果数")
        int maxPerQuery
    ) {
        // 并行调用多个搜索引擎，合并结果
        // 来源: Bing + SerpAPI + 本地知识库
    }
}
```

**多源搜索策略**：同时搜 Bing API + SerpAPI + 本地数据库，合并去重后按相关性排序。

### 7.2 本地数据库查询工具

项目已有的 `backend` 模块包含完整的股票数据库。给 Agent 配上查询工具：

```java
@Tool(name = "query_financials", description = "查询公司财务数据",
      readOnly = true, concurrencySafe = true)
public String queryFinancials(
    @ToolParam(name = "stock_code", description = "6位股票代码")
    String stockCode,
    @ToolParam(name = "years", description = "查询最近N年")
    int years
) { /* 查 stock_finance 表 */ }
```

### 7.3 工具对比：豆包 vs Agent平台

| 能力 | 豆包 | Agent 平台 |
|------|------|-----------|
| 搜索 | 单一搜索源，top_k=10 | 多源并行搜索 + 合并去重 |
| 财务数据 | 依赖搜索结果中的片段 | 本地数据库结构化查询 |
| K线/量能 | 搜索结果+模型估算 | 本地数据库精确查询 |
| 竞品数据 | 逐家搜索 | 一次查询全赛道 |
| 产业链数据 | 模型知识+搜索 | 本地产业链图谱查询 |
| 用户画像 | 无持久化 | 跨会话累积，影响回答风格 |

---

## 八、实施计划

### Phase 1: Agent 平台骨架 (1-2周)

**目标**：MasterOrchestrator + GeneralChatOrchestrator 跑通基础对话

```
□ 搭建 MasterOrchestrator（意图识别 + 插件路由）
□ 搭建 GeneralChatOrchestrator + 3 个专家 Agent
□ 实现 MultiSearchTool（多源并行搜索）
□ AgentScope AgentTeam 配置（AgentCreate/TeamSay）
□ 基础 CLI 交互模式跑通
```

**产出**：能替代豆包/Kimi 进行日常对话（先不接股票分析）

### Phase 2: 股票分析插件对接 (1周)

**目标**：Agent 平台调用 StockTradingSystem 分析管线

```
□ 实现 StockPluginAgent（REST 调用 + SSE 转发）
□ MasterOrchestrator 增加 stock_analysis 意图识别
□ 分析结果在 Agent 对话中自然呈现
```

**产出**：用户说"分析扬杰科技" → Agent 路由到 StockTradingSystem → 返回9维度分析

### Phase 3: 认知分析引擎 (2-3周) ← 核心

**目标**：后台静默学习用户特征

```
□ 搭建 CognitionOrchestrator + 5 个认知 Agent
□ 实现 UserProfile DB 表 + 存取逻辑
□ 每次对话结束后触发认知分析链
□ 认知结果写入 UserProfile，影响 GeneralChatOrchestrator 行为
□ 实现首版认知周报（CognitiveReportAgent）
```

**产出**：Agent 开始"了解你"。使用一周后能看到自己的盲区报告

### Phase 4: 方法论沉淀 + 试用 (远期)

**目标**：可复制的亲友部署流程

```
□ 搭建 FamilyDeployOrchestrator + 3 个服务 Agent
□ 编写"智能体搭建手册"（非技术人员可操作）
□ 第一个亲友试用 + 迭代
□ 画像数据隔离验证（不同 userId 互不影响）
```

---

## 九、技术选型

| 组件 | 选择 | 理由 |
|------|------|------|
| Agent框架 | AgentScope 2.0 | 原生多Agent编排、流式事件、AgentTeam |
| 主力模型 | dashscope:qwen-max | 中文最强推理，StockTradingSystem 验证可用 |
| 辅助模型 | dashscope:qwen-plus | 性价比高，数据查询/搜索 Agent 够用 |
| 搜索API | Bing API + SerpAPI | 双源合并 |
| 状态存储 | RedisAgentStateStore | 生产级多轮对话持久化 |
| 用户画像存储 | H2/MySQL (user_profile 表) | 轻量级，按 userId 隔离 |
| 流式推送 | SSE (Spring Boot) + React | StockTradingSystem 已验证 |

---

## 十、风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| 认知分析不准 | 用户画像误导对话风格 | 初期只影响"回答详细程度"等低风险维度，逐步放开 |
| 多Agent编排增加延迟 | 等待时间长 | 专家Agent全并发；认知分析后台异步 |
| 搜索API费用 | 运营成本高 | 本地DB覆盖80%查询；股票数据走 ST 系统 |
| Token消耗大 | API费用高 | qwen-plus 做子 Agent，max 只用于 Orchestrator |
| 亲友部署门槛 | 非技术人员难上手 | 问卷+引导 Agent 降低门槛，不要求理解技术 |

---

## 十一、成功标准

### 短期（Phase 1-2 完成）

1. **替代豆包/Kimi**：日常对话体验不低于豆包
2. **股票分析一键完成**：说"分析XX"→自动跑完9维度，不比豆包的4轮问答差
3. **多轮上下文保持**：追问不需要重复背景

### 中期（Phase 3 完成）

4. **认知报告可用**：使用2周后生成的首份报告能准确反映用户的真实盲区和偏好
5. **回答风格自适应**：Agent 自动调整为"简洁/详细""先结论/先分析"
6. **盲区提示有效**：Agent 在你回避的领域主动补充解释

### 远期（Phase 4 完成）

7. **亲友可部署**：非技术人员按照手册 30 分钟内完成搭建
8. **画像独立**：同一套代码，不同 userId 产生完全不同的 Agent 行为
9. **方法论可迁移**：核心设计不限于股票分析，可扩展到其他领域

---

> 本计划位于：`D:\code\StockTradingSystem\agent-scope\docs\dev-plan-super-analyst.md`（原型与文档仓库）
> 
> **项目划分**：
> - `D:\code\StockTradingSystem\` — 股票分析后端（分析引擎已完成，含 agent-scope 原型与文档）
> - `D:\code\Agent\` — 个人AI平台（待创建，本计划的核心待建内容）
> 
> 依赖：AgentScope 2.0 + DashScope API
> 
> **当前进度**：StockTradingSystem 分析引擎已完成。Agent 平台（D:\code\Agent）待启动。
> **优先启动**：Phase 1（Agent 平台骨架），边用边迭代认知引擎。
