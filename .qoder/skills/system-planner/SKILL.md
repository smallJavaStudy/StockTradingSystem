---
name: system-planner
description: 规划和搭建股票分析系统。在设计功能、生成项目结构、做架构决策或跟踪实现进度时使用此技能。
---

# 股票分析系统规划器

## 技术栈

| 层 | 技术 | 说明 |
|---|------|------|
| 后端 | Java 17+ / Spring Boot 3 | REST API |
| 数据库 | H2（嵌入式） | 文件存储，零配置，开箱即用 |
| ORM | Spring Data JPA | |
| 前端 | Vue 3 + Vite | 轻量级单页应用 |
| 数据源 | 东方财富原始 API + AKShare（仅财务数据） | 直接调用更稳定，见下方说明 |
| 构建 | Maven | |
| 测试（后端） | JUnit 5 + Mockito + Spring Boot Test | 单元测试 + 集成测试 |
| 测试（前端） | Vitest + Vue Test Utils | 组件测试 |
| 测试（Python） | pytest | 接口测试 |

## 架构总览

```
┌──────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   Vue 3 SPA  │────>│   Spring Boot API    │────>│   H2 数据库     │
│   （前端）    │<────│   （REST + 定时任务）  │<────│   （文件存储）   │
└──────────────┘     └──────────┬───────────┘     └─────────────────┘
                                │
                                │ HTTP
                                ▼
                     ┌──────────────────────────┐
                     │  数据抓取服务 (Python)      │
                     │  东方财富API + AKShare财务  │
                     └──────────────────────────┘
```

## 数据源方案（已验证）

**核心结论：直接对接东方财富原始 API，不依赖 AKShare 的东方财富封装。**

AKShare 调用东方财富源接口时频繁断连（缺少 Referer header），直接调用东方财富原始接口稳定可用。
财务数据使用 AKShare 新浪源（`stock_financial_abstract`），稳定且字段完整。
详细验证报告见 [data-source-verification.md](data-source-verification.md)。

### 数据源优先级

| 优先级 | 数据源 | 用途 | 稳定性 |
|--------|-------|------|--------|
| 1 | 东方财富原始 API | K线、实时行情、资金流向、龙虎榜 | 稳定（需加 Referer） |
| 2 | AKShare 新浪源 | 财务数据 | 较稳定 |
| 3 | AKShare 东方财富源 | 仅作降级备选 | 不稳定 |

### 东方财富已验证接口

| 数据类型 | 接口地址 | 关键参数 | 备注 |
|---------|---------|---------|------|
| 日/周/月K线 | `push2his.eastmoney.com/api/qt/stock/kline/get` | secid=1.600519, klt=101/102/103, fqt=0/1/2 | 返回：日期,开,收,高,低,量,额,振幅,涨跌幅,涨跌额,换手率 |
| 分时行情 | 同上 | klt=1/5/15/30/60 | 返回字段同K线，时间精确到分钟 |
| 实时行情 | `push2.eastmoney.com/api/qt/stock/get` | secid, fields=f43,f44... | ⚠ 价格/百分比需除以100 |
| 资金流向 | `push2his.eastmoney.com/api/qt/stock/fflow/daykline/get` | secid, klt=101 | 主力/超大单/大单/中单/小单 净额+净占比 |
| 龙虎榜 | `datacenter-web.eastmoney.com/api/data/v1/get` | reportName=RPT_DAILYBILLBOARD_DETAILSNEW | 按日期范围查询 |
| 个股基本信息 | 实时行情接口补充 | fields=f84,f85,f116,f117,f189 | 总股本、流通股、总市值、上市日期 |

### 必要的 HTTP Headers

```python
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Referer': 'https://quote.eastmoney.com/'
}
```

**缺了 Referer 会被东方财富服务器断连，这是 AKShare 不稳定的核心原因。**

### secid 编码规则

| 市场 | 代码前缀 | secid 格式 |
|------|---------|-----------|
| 上海 | 6xxxxx | 1.代码 |
| 深圳 | 0xxxxx, 3xxxxx | 0.代码 |
| 北京 | 8xxxxx | 0.代码 |

### AKShare 仅用于财务数据

| 接口函数 | 功能 | 备注 |
|---------|------|------|
| `stock_financial_abstract` | 财务摘要（新浪源） | 返回横表需转置，按季度，指标包括归母净利润、营业总收入、ROE等 |

**技术指标（MA/MACD/RSI/KDJ/布林带）**：无现成数据接口，后端基于 K 线数据自行计算。

## 系统定位

**不是通用看盘工具，是标的深度分析系统。**

核心目的：对用户指定的股票标的做全面梳理和分析，涵盖产业背景、财务数据、市场表现、竞争格局。
用户添加一只股票后，系统自动拉取所有能获取的数据，并提供手动补充框架让用户完善数据源无法覆盖的分析维度。

### 目标用户

个人价值投资者，需要对单只股票进行深度基本面分析，而非短线盯盘。

### 成功标准

1. 用户添加一只股票后，5分钟内能看到完整的自动数据概览（基本信息+行情+估值+财务+股东+研报）
2. 自动数据获取成功率 > 95%（含降级到新浪源的场景）
3. 用户能在30分钟内完成一只股票的手动分析数据补充（产业分析+主营构成+竞争力+笔记）
4. 分析完成度指示器实时显示已填/未填项，不低于60%时概览页不再提示"数据不完整"

### 不做什么（明确边界）

- **不做自动交易**：不连接券商，不下单
- **不做量化回测**：不做策略回测、历史模拟
- **不做组合管理**：不做多股票资产配置、风险平价
- **不做实时盯盘**：不是高频看盘工具，行情刷新频率最低5分钟
- **不做社区/分享**：不做用户间交流、分析分享
- **不做多股票横向对比面板**：除竞品对比功能外，不做批量多股横向对比页面

## 核心功能

### 一、标的概览（自动）

添加股票后自动生成的总览页面，一眼看清标的全貌：
- 基本信息：名称、代码、行业（`INDUSTRY_NAME`）、上市日期、总/流通市值、总/流通股本
- 最新行情：价格、涨跌幅、换手率、振幅
- 估值水平：PE(TTM)、PB、PS、PEG（来自 `RPT_VALUEANALYSIS_DET`）
- 股东集中度：股东总数、户均持股、持股集中度（来自 `RPT_F10_EH_HOLDERNUM`）
- 最近研报：标题、评级、机构、盈利预测（来自 `stock_research_report_em`）

### 二、产业分析（自动 + 手动混合）

**自动获取部分：**
- 行业识别：利润表接口返回行业名称（如"白酒Ⅱ"）
- 行业板块行情：行业整体涨跌趋势（`stock_board_industry_info_ths`）
- ~~行业资金流向：行业整体主力资金进出（`stock_fund_flow_industry`）~~ ⚠ 暂不可用，待验证
- ~~行业估值中位数：通过同行估值对比推断行业估值水平~~ ❌ 无可用数据源（RPT_VALUEANALYSIS_DET 只返回单股历史），改为用户手动录入行业PE/PB中位数

**手动补充框架：**
- 产业生命周期（导入期/成长期/成熟期/衰退期）- 用户选择
- 产业上下游关系 - 富文本编辑
- 产业政策影响 - 富文本编辑
- 行业发展趋势判断 - 富文本编辑
- 行业估值中位数 - 手动录入行业PE/PB中位数（因无自动数据源）

### 三、财务数据（自动）

**利润表**（来源：`RPT_DMSK_FN_INCOME` datacenter 接口）
- 归母净利润、营业总收入、营业成本
- 按季度/年度趋势图
- 同比增长率自动计算

**财务摘要**（来源：AKShare `stock_financial_abstract` 新浪源）
- 每股收益、每股净资产、每股经营现金流
- ROE、毛利率、销售净利率、资产负债率
- 存货周转率、应收账款周转率
- 期间费用率、流动比率、速动比率
- 按季度趋势图

**主营业务构成（手动）**
- 数据源无法自动获取按产品/地区拆分的营收构成
- 提供结构化手动录入框架：
  - 产品/业务名称 + 营收 + 占比 + 毛利率
  - 地区名称 + 营收 + 占比
- 用户可从年报/研报中手动录入，系统负责存储、展示、追踪变化

### 四、市场竞争力分析（自动 + 手动混合）

**自动获取部分：**
- 估值对比：PE/PB/PS 与行业平均对比（来自 `RPT_VALUEANALYSIS_DET`，仅单股历史估值，同行对比需手动指定竞品后计算）
- 市值排名：在同行业中的市值排位（暂无直接数据源，需手动录入或通过竞品列表推算）
- 盈利能力对比：ROE、净利率与同行对比（需手动指定竞品股票，系统自动拉取竞品数据计算）

**手动补充框架：**
- 市场占有率 - 百分比 + 年份
- 核心竞争优势 - 富文本（品牌、技术、渠道、成本等）
- 竞争劣势 - 富文本
- 竞品列表 - 手动添加竞品股票代码，系统自动拉取竞品的行情/财务数据进行并排对比
- 护城河评价 - 用户选择（强/中/弱） + 文字说明

### 五、行情与交易分析（自动）

- K 线图：日/周/月K线，支持前复权/后复权/不复权
- 分时行情：1/5/15/30/60分钟
- 技术指标：MA(5/10/20/60)、MACD、RSI、KDJ、布林带（后端自行计算）
- 资金流向：主力/超大单/大单/中单/小单 净流入额和净占比趋势
- 龙虎榜：上榜原因、买卖金额、上榜后涨幅跟踪

### 六、股东与机构（自动）

- 十大股东/股东人数变化趋势（来自 `RPT_F10_EH_HOLDERNUM`）
- 机构持仓变化（如数据源可用）
- 股东集中度变化趋势图

### 七、研报与观点（自动）

- 最近研报列表：标题、评级、机构、日期、PDF链接（来自 `stock_research_report_em`）
- 盈利预测：未来2-3年EPS预测、目标PE
- 评级分布：买入/增持/中性/减持统计

### 八、投资笔记与提醒（手动 + 自动）

**手动：**
- 投资逻辑：为什么买？核心论点
- 风险点：可能出问题的因素
- 买入/卖出条件：什么情况下操作
- 自由笔记：不限格式

**自动：**
- 价格预警：高于/低于阈值提醒
- 资金异动：主力大额流入/流出提醒
- 信号检测：金叉/死叉、放量、RSI超买超卖

## 项目结构

```
StockTradingSystem/
├── backend/                          # Spring Boot 后端
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/stock/
│       │   ├── StockApplication.java  # 启动类
│       │   ├── config/                # 配置类
│       │   ├── controller/            # REST 控制器
│       │   ├── entity/                # JPA 实体
│       │   ├── repository/            # Spring Data 仓库
│       │   ├── service/               # 业务逻辑
│       │   ├── dto/                   # 请求/响应 DTO
│       │   └── scheduler/             # 定时任务
│       └── test/java/com/stock/       # 后端测试
│           ├── controller/            # 控制器集成测试
│           ├── service/               # 服务单元测试
│           └── repository/            # 仓库测试
├── frontend/                          # Vue 3 + Vite 前端
│   ├── package.json
│   └── src/
│       ├── views/                     # 页面
│       ├── components/                # 可复用组件
│       ├── api/                       # API 客户端
│       ├── stores/                    # Pinia 状态管理
│       └── utils/                     # 工具函数
├── data-fetcher/                      # Python 数据抓取服务
│   ├── requirements.txt
│   ├── app.py                         # FastAPI 封装（东方财富API + AKShare财务）
│   └── tests/                         # Python 接口测试
│       └── test_api.py
└── system-planner/                    # 本技能目录
```

## 实施阶段

1. **第一阶段 - 基础搭建 + 数据管道**：后端骨架 + H2 + 标的增删改查 + Python数据服务（东方财富API + AKShare + datacenter利润表/股东/研报/估值）+ 自动拉取基本信息/行业/估值 + 单元测试 + 接口测试
2. **第二阶段 - 标的概览页**：自动数据总览页面 + 行情展示 + 估值 + 股东 + 研报 + 利润表 + 财务摘要 + 初始化状态展示 + 分析完成度 + 组件测试
3. **第三阶段 - 图表与指标**：K 线图 + 技术指标计算 + 资金流向图 + 财务趋势图 + 指标计算测试
4. **第四阶段 - 手动数据框架**：产业分析编辑 + 主营构成录入 + 竞争力评估 + 竞品对比 + 投资笔记 + 数据校验
5. **第五阶段 - 预警与整合**：价格预警 + 资金异动 + 信号检测 + 标的分析报告导出（HTML格式）+ 端到端测试

## 数据库表设计

### 自动数据表

```sql
-- 标的基本信息（来源: 东方财富实时行情 + 利润表行业字段 + 估值接口）
stock (id, code, name, industry_name, industry_code, total_market_cap, circulating_market_cap, listing_date, total_shares, circulating_shares, pe_ttm, pb, ps_ttm, peg, added_at, is_active)

-- 日 K 线（来源: 东方财富 push2his K线接口）
stock_daily (id, stock_id, trade_date, open, close, high, low, volume, amount, amplitude, change_percent, change_amount, turnover_rate)

-- 资金流向（来源: 东方财富 push2his fflow 接口）
stock_fund_flow (id, stock_id, trade_date, close_price, change_percent, main_net_amount, main_net_percent, huge_net_amount, huge_net_percent, big_net_amount, big_net_percent, mid_net_amount, mid_net_percent, small_net_amount, small_net_percent)

-- 利润表（来源: 东方财富 datacenter RPT_DMSK_FN_INCOME）
stock_income (id, stock_id, report_date, parent_net_profit, total_revenue, operating_cost, report_type)

-- 财务摘要（来源: AKShare 新浪源 stock_financial_abstract）
stock_financial (id, stock_id, report_date, eps, eps_yoy, bvps, ocfps, undistributed_ps, net_profit_margin, roe, roe_diluted, gross_margin, debt_ratio, inventory_turnover, quick_ratio, current_ratio, period_expense_ratio)

-- 股东数据（来源: 东方财富 datacenter RPT_F10_EH_HOLDERNUM）
stock_holder (id, stock_id, end_date, holder_total_num, holder_num_ratio, avg_free_shares, avg_hold_amt, hold_focus, hold_ratio_total)

-- 研报（来源: AKShare stock_research_report_em）
stock_research (id, stock_id, title, rating, institution, report_date, eps_forecast_1y, pe_forecast_1y, eps_forecast_2y, pe_forecast_2y, industry, pdf_url)

-- 技术指标（后端计算）
stock_indicator (id, stock_id, trade_date, ma5, ma10, ma20, ma60, macd, signal, hist, rsi, kdj_k, kdj_d, kdj_j, boll_upper, boll_mid, boll_lower)
```

### 手动数据表

```sql
-- 产业分析（手动）
stock_industry_analysis (id, stock_id, lifecycle_stage, upstream_downstream, policy_impact, industry_trend, updated_at)

- 主营业务构成-按产品（手动）
stock_product_breakdown (id, stock_id, report_date, product_name, revenue, revenue_ratio, gross_margin, created_at)
-- revenue: 单位为元，≥ 0
-- revenue_ratio: 百分比，0-100
-- gross_margin: 百分比，-100~100（允许负毛利）

-- 主营业务构成-按地区（手动）
stock_region_breakdown (id, stock_id, report_date, region_name, revenue, revenue_ratio, created_at)
-- revenue: 单位为元，≥ 0
-- revenue_ratio: 百分比，0-100

-- 竞争力评估（手动）
stock_competitiveness (id, stock_id, market_share, market_share_year, advantage, disadvantage, moat_level, moat_note, updated_at)
-- market_share: 百分比，0-100
-- moat_level: STRONG / MEDIUM / WEAK

-- 竞品列表（手动指定，系统自动拉取数据）
stock_competitor (id, stock_id, competitor_stock_id, created_at)
-- 添加竞品时，若竞品股票不在系统内，自动添加并触发初始化流程

-- 投资笔记（手动）
stock_note (id, stock_id, category, content, created_at, updated_at)
-- category: INVEST_LOGIC / RISK / BUY_CONDITION / SELL_CONDITION / FREE_NOTE
-- content: 必填，长度 1-10000

-- 价格预警（自动检测）
price_alert (id, stock_id, type, threshold, is_triggered, created_at)
-- type: ABOVE / BELOW
-- threshold: 正数，单位为元
```

## 手动数据验证规则

| 表 | 字段 | 规则 |
|----|------|------|
| stock_industry_analysis | lifecycle_stage | 必填，枚举：INTRODUCTORY / GROWTH / MATURE / DECLINE |
| stock_industry_analysis | upstream_downstream | 选填，纯文本，最大10000字符 |
| stock_industry_analysis | policy_impact | 选填，纯文本，最大10000字符 |
| stock_industry_analysis | industry_trend | 选填，纯文本，最大10000字符 |
| stock_product_breakdown | product_name | 必填，1-100字符 |
| stock_product_breakdown | revenue | 必填，≥ 0，单位：元 |
| stock_product_breakdown | revenue_ratio | 必填，0-100 |
| stock_product_breakdown | gross_margin | 选填，-100~100 |
| stock_region_breakdown | region_name | 必填，1-50字符 |
| stock_region_breakdown | revenue | 必填，≥ 0，单位：元 |
| stock_region_breakdown | revenue_ratio | 必填，0-100 |
| stock_competitiveness | market_share | 选填，0-100 |
| stock_competitiveness | market_share_year | 选填，4位整数（如2025） |
| stock_competitiveness | moat_level | 选填，枚举：STRONG / MEDIUM / WEAK |
| stock_competitiveness | advantage | 选填，纯文本，最大10000字符 |
| stock_competitiveness | disadvantage | 选填，纯文本，最大10000字符 |
| stock_competitiveness | moat_note | 选填，纯文本，最大5000字符 |
| stock_competitor | competitor_stock_id | 必填，必须存在于 stock 表，且不等于自身 stock_id |
| stock_note | category | 必填，枚举：INVEST_LOGIC / RISK / BUY_CONDITION / SELL_CONDITION / FREE_NOTE |
| stock_note | content | 必填，1-10000字符 |
| price_alert | type | 必填，枚举：ABOVE / BELOW |
| price_alert | threshold | 必填，> 0 |

## 关键决策

- **H2 数据库**：嵌入式，文件持久化（`~/stock-trading/db`），无需安装
- **Python 微服务抓数据**：东方财富 API 需要正确的 headers，AKShare 封装不可靠，自建封装层
- **按需抓取**：只有用户添加股票时才拉取数据，不做批量导入
- **定时刷新**：Spring @Scheduled 在交易时段定时更新行情

## 数据同步策略

### 增量同步 vs 全量同步

| 数据类型 | 同步方式 | 说明 |
|---------|---------|------|
| K线（日/周/月） | 增量 | 只拉取 trade_date > 本地最大日期 的数据 |
| 分时行情 | 不存储 | 实时从接口获取，不落库 |
| 资金流向 | 增量 | 只拉取 trade_date > 本地最大日期 的数据 |
| 利润表 | 全量对比 | 每次拉最新8个报告期，对比已有数据，有变化则更新 |
| 财务摘要 | 全量对比 | 每次拉最新8个报告期，对比已有数据，有变化则更新 |
| 股东数据 | 全量对比 | 每次拉最新8个报告期，对比已有数据，有变化则更新 |
| 估值数据 | 增量 | 只拉取 trade_date > 本地最大日期 的数据 |
| 研报 | 增量 | 拉取最近10篇，去重入库（按标题+机构+日期去重） |
| 龙虎榜 | 按日期增量 | 拉取最近N天数据，去重入库 |
| 实时行情 | 不存储 | 用于刷新自选股列表中的最新价、涨跌幅等展示字段 |

### 数据更新频率

| 数据类型 | 更新频率 | 说明 |
|---------|---------|------|
| 实时行情 | 交易时段每5分钟 | 9:30-11:30, 13:00-15:00 |
| K线 | 每日收盘后 | 15:30 同步当日数据 |
| 资金流向 | 每日收盘后 | 16:00 同步当日数据 |
| 利润表 | 每周日 02:00 | 季报发布期（1/4/7/10月）可调高为每日 |
| 财务摘要 | 每周日 02:00 | 同上 |
| 股东数据 | 每周日 02:00 | 季报发布期频率可调高 |
| 估值数据 | 每日 18:00 | 每个交易日收盘后更新 |
| 研报 | 每日 18:00 | 每日检查新研报 |
| 龙虎榜 | 每日 18:00 | 每日检查新龙虎榜数据 |
| 技术指标 | 每日收盘后 | K线同步后自动重算 |
| 行业板块行情 | 交易时段每10分钟 | 非核心数据，低优先级 |

### 定时任务时间表

| 任务 | 执行时间 | 说明 |
|------|---------|------|
| 行情刷新 | 交易时段每5分钟 | 9:30-11:30, 13:00-15:00 |
| K线同步 | 每日 15:30 | 收盘后同步当日K线 |
| 资金流向同步 | 每日 16:00 | 收盘后同步当日资金数据 |
| 估值/研报/龙虎榜同步 | 每日 18:00 | 收盘后同步当日新增数据 |
| 财务/利润表/股东同步 | 每周日 02:00 | 季报发布期频率可调高 |
| 预警检测 | 交易时段每1分钟 | 检查价格是否触发预警 |
| 技术指标重算 | 每日 15:45 | K线同步后自动触发 |

### 首次添加股票时的数据初始化

用户添加股票时，后端异步执行（不阻塞 API 响应），前端可通过 `GET /stocks/{id}/init-status` 查询进度：

1. 拉取个股基本信息 + 估值（实时行情接口 + RPT_VALUEANALYSIS_DET）
2. 拉取近1年日K线数据（前复权）
3. 拉取近3个月资金流向数据
4. 拉取最新利润表数据（RPT_DMSK_FN_INCOME，最近8个报告期）
5. 拉取最新财务摘要（AKShare 新浪源，最近8个报告期）
6. 拉取股东数据（RPT_F10_EH_HOLDERNUM，最近8个报告期）
7. 拉取最近研报（stock_research_report_em，最近10篇）
8. 基于K线数据计算技术指标

每步之间间隔 500ms（遵守限流要求），8步预计耗时约 8-12秒（含网络耗时）。

## 错误处理策略

### 外部接口调用

| 场景 | 处理方式 |
|------|---------|
| 东方财富接口超时 | 重试1次（间隔2秒），仍失败则返回缓存数据 + 标记数据过期 |
| 东方财富接口返回空 | 记录日志，返回缓存数据 |
| AKShare 财务接口失败 | 返回错误提示，不阻塞其他功能 |
| 股票代码不存在 | 添加自选股时校验，返回400错误 |
| 接口被限流（429/403） | 退避重试，间隔从5秒指数增长到60秒 |

### 后端统一异常处理

- `@ControllerAdvice` + `@ExceptionHandler` 全局异常捕获
- 自定义业务异常：`StockNotFoundException`、`DataFetchException`、`DuplicateStockException`
- 所有 API 错误响应统一格式：`{ "code": 404, "message": "股票不存在", "timestamp": "..." }`

## 配置管理

### 后端配置（application.yml）

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:h2:file:~/stock-trading/db
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: update

stock:
  data-fetcher:
    url: http://localhost:5001
    timeout: 15000
  scheduler:
    quote-refresh-cron: "0 */5 9-11,13-15 * * MON-FRI"
    kline-sync-cron: "0 30 15 * * MON-FRI"
    fund-flow-sync-cron: "0 0 16 * * MON-FRI"
    alert-check-cron: "0 */1 9-11,13-15 * * MON-FRI"
```

### Python 数据服务配置（环境变量或 .env）

```
DATA_FETCHER_PORT=5001
EASTMONEY_TIMEOUT=15
EASTMONEY_RETRY=1
EASTMONEY_DELAY=0.5
AKSHARE_TIMEOUT=30
```

## CORS 跨域配置

后端 `@Configuration` 类配置 CORS，允许前端开发服务器访问：

```java
allowedOrigins: http://localhost:5173  // Vite 默认端口
allowedMethods: GET, POST, PUT, DELETE
allowedHeaders: "*"
```

## 启动与运行

### 前置条件

- Java 17+
- Python 3.9+
- Node.js 18+

### 启动顺序

```bash
# 1. 启动 Python 数据服务
cd data-fetcher
pip install -r requirements.txt
python app.py
# 等待服务就绪（http://localhost:5001）

# 2. 启动 Spring Boot 后端
cd backend
mvn spring-boot:run
# 等待服务就绪（http://localhost:8080）

# 3. 启动 Vue 前端
cd frontend
npm install
npm run dev
# 访问 http://localhost:5173
```

## 日志策略

| 组件 | 日志级别 | 输出 | 要点 |
|------|---------|------|------|
| Spring Boot | INFO | 控制台 + 文件 | 定时任务执行记录用 INFO，接口调用失败用 WARN |
| Python 数据服务 | INFO | 控制台 | 每次外部接口调用记录 URL + 耗时 + 状态 |
| Vue 前端 | WARN | 浏览器控制台 | API 调用失败记录 |

## 测试策略

### 后端测试（JUnit 5 + Mockito + Spring Boot Test）

| 层级 | 测试类型 | 工具 | 要点 |
|------|---------|------|------|
| Service | 单元测试 | JUnit 5 + Mockito | Mock Repository 和外部 HTTP 调用 |
| Controller | 集成测试 | @WebMvcTest + MockMvc | 验证请求路由、参数校验、响应格式 |
| Repository | 集成测试 | @DataJpaTest + H2 | 验证查询逻辑，使用内存 H2 |
| 全流程 | 端到端测试 | @SpringBootTest | 验证完整调用链路 |

### 前端测试（Vitest + Vue Test Utils）

| 层级 | 测试类型 | 要点 |
|------|---------|------|
| 组件 | 单元测试 | 渲染输出、事件触发、Props 验证 |
| Store | 单元测试 | 状态变更、异步 Action |
| API 客户端 | 单元测试 | Mock axios，验证请求参数 |

### Python 数据服务测试（pytest）

| 层级 | 测试类型 | 要点 |
|------|---------|------|
| 接口 | 集成测试 | Mock 东方财富 HTTP 响应，验证解析逻辑 |
| 数据转换 | 单元测试 | 验证数值除100、横表转纵表等转换逻辑 |
| 错误处理 | 单元测试 | 超时、空数据、异常格式的处理 |

### 测试原则

- 每个功能实现同步编写测试，不延后
- Service 层单元测试覆盖率目标 80%+
- Controller 集成测试覆盖所有 API 端点
- 外部依赖（东方财富API、AKShare）一律 Mock，测试不依赖真实网络
- 每个阶段完成后跑全量测试，确保不回归

## 空数据与加载状态设计（F2/U1）

### 添加股票后的数据加载体验

用户添加股票后，后端异步执行8步初始化，前端体验流程：

1. 添加成功后立即跳转到标的详情页，页面显示**骨架屏**
2. 前端每2秒轮询 `GET /stocks/{id}/init-status` 获取进度
3. 每个数据模块独立展示：数据到达后骨架屏替换为真实数据，未到达的保持骨架屏
4. 初始化失败的项目显示**"数据获取失败，点击重试"**按钮
5. 全部完成后顶部提示"数据加载完成"

### 手动数据为空时的引导

| 模块 | 空状态展示 | 引导文案 |
|------|-----------|---------|
| 产业分析 | 虚线框 + 图标 | "补充产业分析，了解标的所在行业全貌" |
| 主营构成 | 空表格 + 按钮 | "从年报/研报中录入营收构成" |
| 竞争力评估 | 虚线框 + 图标 | "评估市场竞争力，判断投资价值" |
| 竞品列表 | 空列表 + 按钮 | "添加竞品股票，对比分析竞争格局" |
| 投资笔记 | 空列表 + 按钮 | "记录投资逻辑、风险点和操作条件" |

## 前端错误展示设计（U5）

| 场景 | 展示方式 | 说明 |
|------|---------|------|
| 自动数据获取失败 | 数据区域显示 "数据暂时不可用" + 重试按钮 | 不用alert，内联展示 |
| 自动数据过期 | 数据旁显示 ⚠ 图标，hover提示"数据更新于X，可能已过期" | isStale=true时触发 |
| 手动数据保存失败 | 表单顶部红色提示条 "保存失败：XXX"，保留用户输入 | 不丢失输入内容 |
| 股票代码不存在 | 输入框下方红色文字 "该股票代码不存在，请检查输入" | 实时校验 |
| 表单校验失败 | 对应字段下方红色提示文字 | 具体到字段，如"营收不能为负数" |
| 网络断开 | 全局顶部黄色提示条 "网络连接已断开" | 恢复后自动消失 |

## 功能依赖与竞品初始化（F3）

### 竞品初始化流程

用户添加竞品时（`POST /stocks/{id}/competitors`）：
1. 检查竞品股票代码是否已在 stock 表中
2. 如不存在 → 自动添加竞品股票，触发完整初始化流程（8步，间隔500ms）
3. 如已存在 → 直接关联，无需重新初始化
4. 竞品初始化期间，竞品列表中该条目显示"数据加载中"
5. 竞品初始化完成后，竞品对比区域自动更新

**限流影响**：添加竞品需要约8次API调用（≈4秒+网络耗时），期间其他操作需排队。前端应提示"正在获取竞品数据，请稍候"。

### 模块依赖关系

```
标的概览 ←── 基本信息(必须)
         ←── 行情(必须)
         ←── 估值(可选，缺省显示"暂无")
         ←── 股东(可选)
         ←── 研报(可选)

产业分析 ←── 行业识别(必须，来自利润表)
         ←── 行业板块行情(可选)

竞争力   ←── 估值数据(自动)
         ←── 竞品数据(手动触发，竞品需先初始化)

K线图    ←── 日K线数据(必须)
技术指标 ←── K线数据(必须，K线不足则无法计算)

预警     ←── 行情刷新(必须)
信号     ←── 技术指标(必须)
```

## 分析报告导出（F4）

- 格式：**HTML**（浏览器直接打开，可打印为PDF）
- 触发方式：标的详情页右上角"导出分析报告"按钮
- 内容：汇总标的8个模块的关键数据，按模块分节
  - 标的概览：基本信息+估值+股东+研报摘要
  - 产业分析：行业+生命周期+上下游+政策+趋势
  - 财务数据：利润表趋势+财务摘要+主营构成
  - 竞争力：市占率+优劣+竞品对比+护城河
  - 行情：最近60日K线简表+资金流向摘要
  - 投资笔记：全部笔记内容
- 导出时间戳：报告生成时间
- 样式：简洁打印友好，A4排版

## 技术可行性补充

### AKShare 新浪源降级方案（T2）

AKShare 新浪源（`stock_financial_abstract`）如果也不稳定：
- **降级方案1**：尝试东方财富 datacenter 的 RPT_DMSK_FN_INCOME 接口获取部分财务指标（归母净利润、营收已有，ROE/毛利率等可从利润表推算）
- **降级方案2**：财务数据区域显示"暂不可用"，不阻塞其他功能
- **接受风险**：新浪源无第三方备选，极端情况下财务数据功能降级

### 性能预估（T3）

| 场景 | 数据量 | 预估耗时 | 说明 |
|------|-------|---------|------|
| 技术指标计算（5年日K） | 1250条 × 7指标 | <500ms | 纯内存计算，O(n)复杂度 |
| 前端K线图渲染（1年） | 250个数据点 | <200ms | Canvas/WebGL渲染 |
| 前端K线图渲染（5年） | 1250个数据点 | <500ms | 需考虑数据降采样 |
| H2查询（10只股票全量） | ~2万条 stock_daily | <50ms | 索引查询 |
| H2查询（100只股票） | ~20万条 stock_daily | <200ms | H2在百万级以内性能稳定 |

### Mock 方案（Q2）

| 测试层 | Mock 工具 | Mock 对象 | 数据维护 |
|-------|----------|----------|---------|
| Spring Boot Service | Mockito | Repository + RestTemplate（调Python服务） | 固定Java fixture |
| Spring Boot Controller | MockMvc | Service 层 | 固定Java fixture |
| Python 数据服务 | pytest-mock + responses | 东方财富/AKShare HTTP 响应 | JSON fixture 文件 |
| 前端组件 | Vitest vi.mock | axios API 调用 | 固定JS fixture |

原则：Mock 数据与真实接口返回结构一致，但不追求与真实数据值完全相同。在 Python 数据服务层使用 `responses` 库 Mock 东方财富 HTTP 响应，在 Spring Boot 层使用 Mockito Mock 对 Python 服务的 HTTP 调用。

## 需求定稿声明

**本需求文档已于 2026-04-19 完成自检和风险项确认，正式冻结。后续变更需走需求变更流程。**

### 已验证结论

| 项 | 结论 | 验证方式 |
|----|------|---------|
| datacenter-web 与 push2 限流独立性 | **独立**，可并行调用不同域名 | 实测3次交替调用均成功 |
| 利润表金额精度 | **原始值（元），不需要÷100** | 茅台归母净利润823.20亿，量级正确 |
| 行业估值中位数 | **无自动数据源**，改为手动录入 | RPT_VALUEANALYSIS_DET只返回单股历史 |
| 行业资金流向 | **暂不可用**，待后续验证 | stock_fund_flow_industry未验证 |

### 已接受风险

| 风险 | 影响 | 应对 |
|------|------|------|
| AKShare 新浪源无第三方替代 | 财务数据可能不可用 | 降级显示"暂不可用" |
| datacenter 部分字段精度待确认 | holder_num_ratio/hold_ratio_total可能需÷100 | 开发时逐字段验证，记录到映射文档 |
| 行业板块行情依赖同花顺源 | 行业名称可能与东财不一致 | 开发时做名称映射表 |
| 前端K线大数据量渲染 | 5年数据可能卡顿 | 实现数据降采样，默认展示1年 |

## 补充资料

- API 详细设计见 [api-design.md](api-design.md)
- 数据抓取服务详情见 [data-fetcher.md](data-fetcher.md)
- 数据源验证报告见 [data-source-verification.md](data-source-verification.md)
