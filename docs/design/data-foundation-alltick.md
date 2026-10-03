# 数据基础域设计 — 基于 AllTick 渠道

**版本**: v1.1  
**日期**: 2026-07-07  
**API Key**: `6f6c04b9f294033a71e2bcb192a64c45-c-app`  
**实测结论**: 免费计划对 **港股全功能开放**，A股/美股仅开放静态信息接口。

## 0. 实测验证结果（2026-07-07）

### 0.1 接口实测矩阵

| 接口 | A股 (600519.SH) | 港股 (700.HK) | 美股 (AAPL.US) |
|------|:--:|:--:|:--:|
| `/static_info` | ✅ 200 | — | ✅ 200 |
| `/kline` | ❌ 604 | ✅ 200 | ❌ 604 |
| `/trade-tick` | ❌ 604 | ✅ 200 | — |
| `/depth-tick` | — | — | — |
| `/batch-kline` | — | — | — |

### 0.2 关键发现

1. **港股代码格式**：必须去掉前导零。`700.HK` ✅，`00700.HK` ❌（返回600 code invalid）
2. **A股/美股限制**：免费计划对A股和美股仅开放 `static_info`，K线和实时行情均返回 604（code unauthorized）
3. **K线数据正确**：700.HK 日K线返回的 OHLCV 数据与交易日一致，timestamp 为 epoch 秒
4. **实时行情**：`trade-tick` 返回最新一口成交，`tick_time` 为 epoch 毫秒
5. **静态信息含财务指标**：`static_info` 返回 EPS、EPS_TTM、BPS、dividend_yield 等（A股和美股可用）

### 0.3 影响：数据源策略调整

```
                ┌──────────────────────────────────┐
                │         AllTick (免费计划)         │
                │                                  │
                │  港股：全功能（K线+行情+静态信息）   │
                │  A股  ：仅静态信息（EPS/BPS等）     │
                │  美股：仅静态信息（EPS/BPS等）      │
                └──────────┬───────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │ 港股系统  │    │ A股K线   │    │ A股行情  │
    │ 全AllTick│    │ 需东方财富 │    │ 需东方财富 │
    │          │    │ 或升级套餐 │    │ 或升级套餐 │
    └──────────┘    └──────────┘    └──────────┘
```

**这意味着**：如果系统主要面向 A 股分析，AllTick 免费计划只能补充静态信息（EPS/BPS），K线和实时行情仍需东方财富渠道。如果需要 AllTick 覆盖 A 股全量数据，需升级到"全部A股"套餐。

---

## 1. AllTick API 能力总览

### 1.1 接口清单

| 接口 | 方法 | 端点 | 用途 | 批量支持 |
|------|------|------|------|---------|
| K线查询 | GET | `/quote-stock-b-api/kline` | 单产品历史K线（最多500根） | 否 |
| 批量K线 | POST | `/quote-stock-b-api/batch-kline` | 多产品最新2根K线 | 是 |
| 最新成交价 | GET | `/quote-stock-b-api/trade-tick` | 最新逐笔成交（实时价） | 是（最多50码） |
| 盘口深度 | GET | `/quote-stock-b-api/depth-tick` | 五档买卖盘口（A股） | 是（最多50码） |
| 静态信息 | GET | `/quote-stock-b-api/static_info` | 股票基本信息+EPS/BPS | 是（最多50码） |
| WS实时行情 | WSS | `/quote-stock-b-ws-api` | 实时逐笔成交推送 | 订阅模式 |
| WS实时盘口 | WSS | `/quote-stock-b-ws-api` | 实时盘口深度推送 | 订阅模式 |

### 1.2 品种代码格式

| 市场 | 格式 | 示例 |
|------|------|------|
| A股上海 | `.SH` 后缀 | `600519.SH` |
| A股深圳 | `.SZ` 后缀 | `000001.SZ` |
| 港股 | `.HK` 后缀 | `00700.HK` |
| 美股 | `.US` 后缀 | `AAPL.US` |

### 1.3 K线类型

| kline_type | 含义 | 股票支持 |
|------------|------|---------|
| 1 | 1分钟 | 是 |
| 2 | 5分钟 | 是 |
| 3 | 15分钟 | 是 |
| 4 | 30分钟 | 是 |
| 5 | 小时 | 是 |
| 8 | 日K | 是 |
| 9 | 周K | 是 |
| 10 | 月K | 是 |

> 注：2小时（6）和4小时（7）仅外汇/加密货币支持，股票不支持。复权类型目前仅支持 `0`（除权）。

### 1.4 静态信息返回字段

`/static_info` 返回字段：`board`, `bps`, `circulating_shares`, `currency`, `dividend_yield`, `eps`, `eps_ttm`, `exchange`, `hk_shares`, `lot_size`, `name_cn`, `name_en`, `name_hk`, `symbol`, `total_shares`

### 1.5 免费计划限流（当前 API Key 套餐）

| 限制维度 | 数值 |
|----------|------|
| 单接口间隔 | 每 10 秒 1 次 |
| 全部接口合计 | 每分钟 10 次 |
| 每日总上限 | 1,000 次 |
| `/kline` 单次最大 | 500 根 |
| `/batch-kline` 单次最大 | 2 根 / 最多 5 组 |
| `/trade-tick` 单次最大 | 5 个代码 |
| `/static_info` 单次最大 | 5 个代码 |

---

## 2. AllTick 能力缺口分析

### 2.1 覆盖矩阵

| 数据类别 | AllTick | 东方财富 | AKShare | 结论 |
|----------|:--:|:--:|:--:|------|
| 实时行情 | ✅ | ✅ | — | AllTick 主用 |
| 历史K线 | ✅ | ✅ | — | AllTick 主用 |
| 盘口深度 | ✅ | — | — | AllTick 独占 |
| 基础信息（名称/交易所） | ✅ | ✅ | — | AllTick 主用 |
| EPS / BPS / 分红率 | ✅ | — | — | AllTick 独占（基础版） |
| 利润表（营收/净利润） | ❌ | ✅ | — | 需东方财富 |
| 完整财务指标（ROE/毛利率/负债率等） | ❌ | — | ✅ | 需 AKShare |
| 估值数据（PE/PB/PS/PEG） | ❌ | ✅ | — | 需东方财富 |
| 股东数据 | ❌ | ✅ | — | 需东方财富 |
| 资金流向 | ❌ | ✅ | — | 需东方财富 |
| 研报 | ❌ | — | ✅ | 需 AKShare |
| 行业分类 | ❌ | ✅ | — | 需东方财富 |
| 龙虎榜 | ❌ | ❌ | ❌ | 需东方财富 |

### 2.2 结论：多源互补架构

AllTick 是**纯市场数据**（行情+K线+盘口+基础EPS），不覆盖财务深度数据。

```
                ┌─────────────────────────────┐
                │      数据基础域              │
                │                             │
                │  ┌──────────┐               │
                │  │ AllTick  │  ← 实时行情    │
                │  │ (行情层)  │    K线历史     │
                │  └──────────┘    盘口深度     │
                │                   基础 EPS    │
                │  ┌──────────┐               │
                │  │ 东方财富  │  ← 利润表     │
                │  │ (财务层)  │    估值 PE/PB  │
                │  └──────────┘    股东/资金    │
                │                   行业分类    │
                │  ┌──────────┐               │
                │  │ AKShare  │  ← 财务摘要    │
                │  │ (补充层)  │    研报       │
                │  └──────────┘               │
                └─────────────────────────────┘
```

---

## 3. 数据库实体设计（基于 AllTick + 互补源）

### 3.1 表结构总览

```
stock (核心表 — 混合源)
├── stock_daily (日K线 — AllTick)
├── stock_minute (分钟K线 — AllTick，可选)
├── stock_depth (盘口快照 — AllTick，可选)
├── stock_income (利润表 — 东方财富)
├── stock_financial (财务摘要 — AKShare)
├── stock_holder (股东数据 — 东方财富)
├── stock_fund_flow (资金流向 — 东方财富)
├── stock_research (研报 — AKShare)
└── stock_indicator (技术指标 — 本地计算)
```

### 3.2 核心表 DDL

```sql
-- stock: 股票基本信息（AllTick + 东方财富混合填充）
CREATE TABLE stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- AllTick 提供
    code VARCHAR(12) NOT NULL UNIQUE,        -- AllTick格式: 600519.SH
    name_cn VARCHAR(50),                     -- AllTick static_info
    exchange VARCHAR(10),                    -- AllTick: "SSE" / "SZSE"
    currency VARCHAR(10) DEFAULT 'CNY',      -- AllTick
    lot_size INTEGER DEFAULT 100,            -- AllTick
    total_shares BIGINT,                     -- AllTick
    circulating_shares BIGINT,               -- AllTick
    eps DECIMAL(12,4),                       -- AllTick
    eps_ttm DECIMAL(12,4),                   -- AllTick
    bps DECIMAL(12,4),                       -- AllTick
    dividend_yield DECIMAL(8,4),             -- AllTick
    -- 东方财富提供
    industry_name VARCHAR(50),               -- 利润表 INDUSTRY_NAME
    industry_code VARCHAR(20),               -- 行业代码
    listing_date DATE,                       -- push2 实时行情
    total_market_cap BIGINT,                 -- push2
    circulating_market_cap BIGINT,           -- push2
    pe_ttm DECIMAL(10,2),                    -- datacenter 估值
    pb DECIMAL(10,2),                        -- datacenter 估值
    ps_ttm DECIMAL(10,2),                    -- datacenter 估值
    peg DECIMAL(10,2),                       -- datacenter 估值
    -- 行情快照（双源：AllTick WS 推送 + 东方财富定时刷新）
    latest_price DECIMAL(10,2),
    change_percent DECIMAL(8,2),
    change_amount DECIMAL(10,2),
    volume BIGINT,
    turnover_rate DECIMAL(8,4),
    amplitude DECIMAL(8,4),
    -- 系统字段
    added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE
);

-- stock_daily: 日K线（AllTick）
CREATE TABLE stock_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    open_price DECIMAL(12,2),
    close_price DECIMAL(12,2),
    high_price DECIMAL(12,2),
    low_price DECIMAL(12,2),
    volume BIGINT,
    turnover DECIMAL(20,2),
    FOREIGN KEY (stock_id) REFERENCES stock(id),
    UNIQUE(stock_id, trade_date)
);
CREATE INDEX idx_stock_daily_stock ON stock_daily(stock_id);

-- stock_depth: 盘口快照（AllTick — 可选，用于盘中分析）
CREATE TABLE stock_depth (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id BIGINT NOT NULL,
    snapshot_time TIMESTAMP NOT NULL,
    bid1_price DECIMAL(12,2), bid1_volume BIGINT,
    bid2_price DECIMAL(12,2), bid2_volume BIGINT,
    bid3_price DECIMAL(12,2), bid3_volume BIGINT,
    bid4_price DECIMAL(12,2), bid4_volume BIGINT,
    bid5_price DECIMAL(12,2), bid5_volume BIGINT,
    ask1_price DECIMAL(12,2), ask1_volume BIGINT,
    ask2_price DECIMAL(12,2), ask2_volume BIGINT,
    ask3_price DECIMAL(12,2), ask3_volume BIGINT,
    ask4_price DECIMAL(12,2), ask4_volume BIGINT,
    ask5_price DECIMAL(12,2), ask5_volume BIGINT,
    FOREIGN KEY (stock_id) REFERENCES stock(id)
);

-- 以下表字段沿原有设计，来源标注变更
-- stock_income: 东方财富 datacenter（不变）
-- stock_financial: AKShare 新浪源（不变）
-- stock_holder: 东方财富 datacenter（不变）
-- stock_fund_flow: 东方财富 push2his（不变）
-- stock_research: AKShare 东财源（不变）
-- stock_indicator: 本地计算（不变，输入改为 AllTick K线）
```

---

## 4. AllTick 接口调用设计

### 4.1 基础 URL 与认证

```
Base URL: https://quote.alltick.co/quote-stock-b-api
WebSocket: wss://quote.alltick.co/quote-stock-b-ws-api
认证方式: token 作为 URL query 参数
```

### 4.2 代码格式适配层

系统内部以 6 位纯数字 `600519` 存储，调用 AllTick 时转换为 `600519.SH`：

```java
// 代码转换规则
public class StockCodeAdapter {
    public static String toAllTick(String internalCode) {
        if (internalCode.startsWith("6")) return internalCode + ".SH";  // 上海
        if (internalCode.startsWith("0") || internalCode.startsWith("3")) return internalCode + ".SZ";  // 深圳
        throw new IllegalArgumentException("Unknown market: " + internalCode);
    }
    
    public static String fromAllTick(String alltickCode) {
        // "600519.SH" -> "600519"
        return alltickCode.substring(0, 6);
    }
}
```

### 4.3 K线历史拉取（初始化核心流程）

```
GET /quote-stock-b-api/kline
  ?token=xxx
  &query={"trace":"uuid","data":{"code":"600519.SH","kline_type":8,"kline_timestamp_end":0,"query_kline_num":500,"adjust_type":0}}

响应:
{
  "ret": 200,
  "msg": "ok",
  "data": {
    "code": "600519.SH",
    "kline_type": 8,
    "kline_list": [
      {"timestamp":"1677829200","open_price":"1800.00","close_price":"1810.00","high_price":"1820.00","low_price":"1795.00","volume":"5000000","turnover":"9050000000.00"}
    ]
  }
}
```

**分页拉取策略**（免费计划，每 10 秒 1 次）：
1. 首次请求 `query_kline_num=500`，`kline_timestamp_end=0`，获取最新 500 根
2. 拿到最早的 `timestamp`，作为下一次的 `kline_timestamp_end`，再拉 500 根
3. 循环直到覆盖目标时间范围（如 5 年）
4. 在线程池中串行执行，遵守 10 秒间隔

**耗时估算**（免费计划）：
- 5 年 ≈ 1250 个交易日，需 3 次请求（500+500+250）
- 3 × 10 秒 = 30 秒 / 只
- 10 只股票 = 5 分钟

### 4.4 增量更新——批量K线

```
POST /quote-stock-b-api/batch-kline?token=xxx
Body:
{
  "trace": "uuid",
  "data": {
    "data_list": [
      {"code":"600519.SH","kline_type":8,"kline_timestamp_end":0,"query_kline_num":2,"adjust_type":0}
    ]
  }
}
```

**每日更新策略**：
- 盘后对所有 active 股票调用 `/batch-kline`
- 每次 5 组（免费计划上限），间隔 10 秒
- 获取到的新日期 K线 insert or ignore 入库

### 4.5 实时行情——WebSocket 推送

```
连接: wss://quote.alltick.co/quote-stock-b-ws-api?token=xxx

// 订阅（cmd_id=22004）
{"cmd_id":22004,"seq_id":1,"trace":"uuid","data":{"symbol_list":[{"code":"600519.SH"}]}}

// 推送（cmd_id=22998）
{"cmd_id":22998,"data":{"code":"600519.SH","seq":123,"tick_time":1605509068000,"price":"1810.00","volume":"100","turnover":"181000.00","trade_direction":1}}

// 心跳：每 10 秒一次（超过 30 秒无心跳断开）
```

**设计决策**：
- WebSocket 用于**实时页面刷新**（详情页的行情卡片实时跳动）
- 同时保留 HTTP `/trade-tick` 作为降级方案
- 连接管理：单例 WebSocket 连接 → 订阅所有 active 股票 → 回调更新 Pinia Store + 定时落库

### 4.6 静态信息获取

```
GET /quote-stock-b-api/static_info?token=xxx
  &query={"trace":"uuid","data":{"symbol_list":[{"code":"600519.SH"}]}}
```

响应中的字段映射：
- `name_cn` → `stock.name_cn`
- `exchange` → `stock.exchange`（"SSE"→上海, "SZSE"→深圳）
- `total_shares` → `stock.total_shares`
- `circulating_shares` → `stock.circulating_shares`
- `eps` / `eps_ttm` → `stock.eps` / `stock.eps_ttm`
- `bps` → `stock.bps`
- `dividend_yield` → `stock.dividend_yield`
- `lot_size` → `stock.lot_size`
- `board` → 辅助分类

---

## 5. 数据同步策略

### 5.1 初始化流程（添加新标的时）

```
用户输入 600519（6位码）
    │
    ├─ [同步] 调 AllTick /static_info   → 写 stock 基本信息+EPS/BPS
    ├─ [同步] 返回 stock 基本信息给前端
    │
    └─ [异步] DataInitService:
         ├─ Step1: AllTick /kline (3次, 各隔10s) → stock_daily
         ├─ Step2: 本地计算技术指标           → stock_indicator
         ├─ Step3: 东方财富 /valuation        → stock 估值字段
         ├─ Step4: 东方财富 /income           → stock_income + industry
         ├─ Step5: 东方财富 /fund-flow        → stock_fund_flow
         ├─ Step6: AKShare /financial         → stock_financial
         ├─ Step7: 东方财富 /holders          → stock_holder
         └─ Step8: AKShare /research          → stock_research
```

与原有设计的主要变化：Step1 从东方财富 K线改为 AllTick K线，增加了代码适配步骤，其余不变。

### 5.2 定时刷新策略

| 数据 | 频率 | 数据源 | 方式 |
|------|------|--------|------|
| 实时行情 | 盘中推送 | AllTick WS | push → 更新内存 |
| K线增量 | 每日盘后 15:30 | AllTick batch-kline | 逐只拉取最新2根 |
| 技术指标 | K线更新后 | 本地计算 | 增量重算 |
| 估值 | 每日盘后 16:00 | 东方财富 | 逐只刷新 |
| 资金流向 | 每日盘后 16:00 | 东方财富 | 逐只刷新 |
| 利润表 | 每周日 | 东方财富 | 全量对比 |
| 财务摘要 | 每周日 | AKShare | 全量对比 |
| 股东 | 每周日 | 东方财富 | 全量对比 |
| 研报 | 每日盘后 | AKShare | 增量 |

### 5.3 限流调度器

由于免费计划限制：所有接口合计每分钟 10 次（约 6 秒/次），需要全局调度：

```java
// 全局请求队列（单线程执行器串行化所有外部调用）
// 每次调用后 sleep(6000) 保底
// 同源调用合并：同一数据源的多只股票在单次批量请求中完成
// 优先级：用户操作 > 初始化 > 定时刷新
```

---

## 6. 接口调用验证计划

### 6.1 需验证的接口（优先级排序）

| 优先级 | 接口 | 验证内容 |
|--------|------|---------|
| P0 | `/kline?code=600519.SH&kline_type=8` | 是否返回日K线数据，字段完整性 |
| P0 | `/static_info?code=600519.SH` | 字段映射正确性，哪些字段有值哪些为空 |
| P1 | `/trade-tick?code=600519.SH` | 实时价是否与行情软件一致 |
| P1 | `/batch-kline` | 批量功能是否正常 |
| P2 | WebSocket 实时推送 | 推送延迟、断线重连行为 |
| P2 | `/depth-tick` | 五档盘口数据可用性 |

### 6.2 待确认的关键问题

1. **EPS/BPS 准确性**：AllTick 返回的 `eps_ttm` 是否与东方财富一致？需交叉验证。
2. **成交量单位**：`volume` 字段是"股"还是"手"？文档写"成交数量"但单位未明确。
3. **A股代码覆盖度**：是否覆盖全部 5000+ 只 A股？Google Sheet 中搜索不到即不支持。
4. **盘口数据延迟**：免费计划盘口深度是否有额外延迟？
5. **WebSocket 心跳协议**：文档未说明心跳消息格式（是标准 Ping 帧还是自定义 JSON），需实测。

---

## 7. 数据基础域架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot (后端)                        │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                  DataFoundationFacade                 │  │
│  │    (统一入口：根据数据类别路由到不同 Client)          │  │
│  └──────┬───────────────────────┬───────────────────────┘  │
│         │                       │                          │
│  ┌──────▼──────────┐  ┌─────────▼────────┐  ┌────────────┐ │
│  │ AllTickClient   │  │ EMFetcherClient  │  │AKShareClient│ │
│  │ (HTTP + WS)     │  │ (通过Python代理) │  │(通过Python) │ │
│  │                 │  │                  │  │             │ │
│  │ /kline          │  │ /stock/income    │  │/financial   │ │
│  │ /batch-kline    │  │ /stock/valuation │  │/research    │ │
│  │ /trade-tick     │  │ /stock/fund-flow │  │             │ │
│  │ /static_info    │  │ /stock/holders   │  │             │ │
│  │ /depth-tick     │  │ /stock/quote     │  │             │ │
│  │ WS: realtime    │  │ /industry/*      │  │             │ │
│  └──────┬──────────┘  └─────────┬────────┘  └──────┬─────┘ │
│         │                       │                   │       │
│  ┌──────▼──────────┐  ┌─────────▼──────────────────▼─────┐ │
│  │  GlobalRateQueue│  │     Python Data Fetcher           │ │
│  │  (串行调度+限流) │  │     (FastAPI :5001)              │ │
│  └─────────────────┘  └──────────────────────────────────┘ │
│                                       │                    │
│  ┌────────────────────────────────────┼────────────────────┤
│  │                       H2 Database  │                    │
│  │  stock / stock_daily / stock_depth │                    │
│  │  stock_income / stock_financial    │                    │
│  │  stock_holder / stock_fund_flow    │                    │
│  │  stock_research / stock_indicator  │                    │
│  └────────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────┘
```

---

## 8. 实施建议

### 8.1 分步推进

| 阶段 | 内容 | 依赖 |
|------|------|------|
| **Phase 1** | AllTickClient 实现 + 代码适配器 + K线+静态信息集成 | 仅 AllTick |
| **Phase 2** | 东方财富 Python 代理修复（按需） | Python DataFetcher |
| **Phase 3** | AKShare 财务+研报通道恢复 | Python DataFetcher |
| **Phase 4** | WebSocket 实时推送 + 盘口深度 | AllTick WS |
| **Phase 5** | 全局限流调度器 + 免费计划适配 | 全部就绪 |

### 8.2 当前即可开始的工作

1. **验证 AllTick API 可连通性**：用 `curl` 测试 `/static_info?code=600519.SH`
2. **实现 `AllTickClient`**：封装 HTTP 调用 + JSON 解析
3. **实现 `StockCodeAdapter`**：6位码 ↔ AllTick 格式双向转换
4. **改造现有 `StockBasic` Entity**：增加 AllTick 特有字段（eps_ttm, bps, dividend_yield 等）

---

## 附录：错误码速查

| 码 | 含义 |
|----|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | Token 无效或过期 |
| 402 | query 参数错误 |
| 429 | 频率超限 |
| 600 | 产品代码无效 |
| 601 | 请求体为空 |
| 603 | Token 级别不够（请求数量超限） |
| 604 | 无权访问该代码 |
| 605 | HTTP 频率超限 |
| 606 | WebSocket 频率超限，连接将关闭 |
