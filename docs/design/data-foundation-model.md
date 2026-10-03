# 数据基础域 — 数据模型设计

**版本**: v1.0  
**日期**: 2026-07-07  
**设计依据**: 参照 AllTick API 文档反推 + 同花顺/天眼查可查数据项  
**存储引擎**: MySQL 8.0+ / InnoDB  
**数据填充方式**: kimi 智能体（读取此模型，从同花顺 + 天眼查获取数据后写入）

---

## 设计说明

本文档不绑定任何具体 API。AllTick、同花顺、天眼查等渠道的接口文档，统一作为**参考模板**——通过分析它们"能提供什么字段"，反推出一个股票分析系统应该在数据层"拥有什么字段"。实际数据填充由内置的 kimi 智能体负责。

## 模型分为 8 个数据域

```
标的基础域              行情域              市场域
┌─────────────┐    ┌─────────────┐    ┌────────────────┐
│ stock       │    │ stock_quote │    │ market_index    │
│ stock_company│   │ stock_daily │    │ market_aggregate│
└──────┬──────┘    └─────────────┘    └────────────────┘
       │
       ├──────────────────────────────────────────────────┐
       │                    │                             │
  财务域              股东域                         估值域
┌──────────────┐  ┌─────────────┐              ┌──────────────┐
│ stock_income │  │ stock_holder│              │stock_valuation│
│ stock_balance│  │stock_top_holder│            └──────────────┘
│ stock_cashflow│ └─────────────┘
│stock_financial│
└──────────────┘
       │
       ├──────────────────────────────────────────────────┐
       │                    │                             │
  资金流域              筹码域                       产业链域
┌─────────────────┐ ┌──────────────────┐  ┌─────────────────────┐
│stock_money_flow │ │stock_chip_structure│ │stock_industry_chain │
└─────────────────┘ └──────────────────┘  │stock_product_breakdown│
                                          │stock_competitor      │
                                          └─────────────────────┘
```

## 6. 市场域 (2026-07-08 新增)

数据来源：同花顺指数行情 / kimi 智能体（同花顺）

### 6.1 market_index — 市场指数日K线

```sql
CREATE TABLE market_index (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    index_code      VARCHAR(12)   NOT NULL       COMMENT '指数代码: 000001=上证指数, 399001=深证成指, 399006=创业板指, 000688=科创50, 000300=沪深300',
    index_name      VARCHAR(50)   NOT NULL       COMMENT '指数中文名称',
    trade_date      DATE NOT NULL,
    -- OHLCV
    open_price      DECIMAL(12,3) NOT NULL,
    close_price     DECIMAL(12,3) NOT NULL,
    high_price      DECIMAL(12,3) NOT NULL,
    low_price       DECIMAL(12,3) NOT NULL,
    volume          BIGINT                       COMMENT '成交量(手)',
    turnover        DECIMAL(20,2)                COMMENT '成交额(元)',
    -- 涨跌
    change_percent  DECIMAL(10,2)                COMMENT '涨跌幅(%)',
    change_amount   DECIMAL(12,3)                COMMENT '涨跌额',

    UNIQUE KEY uk_index_date (index_code, trade_date),
    INDEX idx_index_name_date (index_name, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场指数日K线';
```

### 6.2 market_aggregate — 全市场聚合数据

数据来源：同花顺市场总览 / kimi 智能体（同花顺）

```sql
CREATE TABLE market_aggregate (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date          DATE NOT NULL UNIQUE,
    -- 成交量能
    total_volume        BIGINT                     COMMENT '全市场成交量(股)',
    total_turnover      DECIMAL(20,2)              COMMENT '全市场成交额(元)',
    -- 市场宽度
    up_count            INT                         COMMENT '上涨家数',
    down_count          INT                         COMMENT '下跌家数',
    flat_count          INT                         COMMENT '平盘家数',
    -- 涨跌停
    limit_up_count      INT                         COMMENT '涨停家数',
    limit_down_count    INT                         COMMENT '跌停家数',
    -- 资金
    main_net_inflow     DECIMAL(20,2)              COMMENT '全市场主力净流入(元)',
    north_net_inflow    DECIMAL(20,2)              COMMENT '北向资金净流入(元)',

    INDEX idx_market_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全市场聚合数据';
```

## 7. 资金流域 (2026-07-08 新增)

数据来源：同花顺资金流向 / kimi 智能体（同花顺）

### 7.1 stock_money_flow — 个股资金流向

```sql
CREATE TABLE stock_money_flow (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id                BIGINT NOT NULL,
    trade_date              DATE NOT NULL,
    -- 主力资金
    main_net_inflow         DECIMAL(20,2)           COMMENT '主力净流入(元)',
    main_inflow_ratio       DECIMAL(10,2)           COMMENT '主力净流入占比(%)',
    -- 分类净流入
    super_large_net_inflow  DECIMAL(20,2)           COMMENT '超大单净流入(元)',
    large_net_inflow        DECIMAL(20,2)           COMMENT '大单净流入(元)',
    medium_net_inflow       DECIMAL(20,2)           COMMENT '中单净流入(元)',
    small_net_inflow        DECIMAL(20,2)           COMMENT '小单净流入(元)',
    -- 分类占比
    super_large_ratio       DECIMAL(10,2)           COMMENT '超大单占比(%)',
    large_ratio             DECIMAL(10,2)           COMMENT '大单占比(%)',
    medium_ratio            DECIMAL(10,2)           COMMENT '中单占比(%)',
    small_ratio             DECIMAL(10,2)           COMMENT '小单占比(%)',

    UNIQUE KEY uk_moneyflow_date (stock_id, trade_date),
    INDEX idx_moneyflow_date (trade_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个股资金流向';
```

## 8. 筹码域 (2026-07-08 新增)

数据来源：同花顺筹码分布 / kimi 智能体（同花顺）

### 8.1 stock_chip_structure — 筹码分布

```sql
CREATE TABLE stock_chip_structure (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id                BIGINT NOT NULL,
    trade_date              DATE NOT NULL,
    -- 筹码集中度
    chip_concentration      VARCHAR(20)              COMMENT '筹码集中度: HIGHLY_CONCENTRATED=高度集中, CONCENTRATED=集中, DISPERSED=分散, HIGHLY_DISPERSED=高度分散',
    avg_cost                DECIMAL(12,3)            COMMENT '平均成本(元)',
    -- 获利/套牢
    profit_ratio            DECIMAL(10,2)            COMMENT '获利盘比例(%)',
    loss_ratio              DECIMAL(10,2)            COMMENT '套牢盘比例(%)',
    -- 筹码密集区
    chip_dense_low          DECIMAL(12,3)            COMMENT '筹码密集区下限(元)',
    chip_dense_high         DECIMAL(12,3)            COMMENT '筹码密集区上限(元)',
    chip_dense_ratio        DECIMAL(10,2)            COMMENT '密集区筹码占比(%)',
    -- 人均持股
    avg_hold_shares         BIGINT                    COMMENT '人均持股(股)',
    top10_hold_ratio        DECIMAL(10,2)            COMMENT '前十大股东持股比例(%)',

    UNIQUE KEY uk_chip_date (stock_id, trade_date),
    INDEX idx_chip_date (trade_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='筹码分布';
```

## 9. 产业链域 (2026-07-07 新增)

### 9.1 stock_industry_chain — 产业链分析

数据来源：kimi 智能体（同花顺+天眼查）

```sql
CREATE TABLE stock_industry_chain (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL UNIQUE        COMMENT '关联 stock.id，1:1关系',
    -- 行业定位
    core_product        VARCHAR(200)                  COMMENT '主营产品/业务描述',
    industry_position   VARCHAR(20)                   COMMENT '产业链位置: UPSTREAM=上游, MIDSTREAM=中游, DOWNSTREAM=下游, IDM=全产业链',
    -- 产业链分析
    upstream            TEXT                          COMMENT '上游原材料、供应商描述',
    downstream          TEXT                          COMMENT '下游应用领域、客户描述',
    key_customers       VARCHAR(500)                  COMMENT '主要客户（知名下游企业）',
    key_suppliers       VARCHAR(500)                  COMMENT '主要供应商',
    -- 行业周期
    lifecycle_stage     VARCHAR(20)                   COMMENT '行业生命周期: INTRODUCTORY=导入期, GROWTH=成长期, MATURE=成熟期, DECLINE=衰退期',
    lifecycle_note      TEXT                          COMMENT '生命周期判断依据',
    -- 政策环境
    policy_impact       TEXT                          COMMENT '产业政策影响分析（支持/限制/中性）',
    policy_detail       TEXT                          COMMENT '具体政策文件或方向',
    -- 行业发展
    industry_trend      TEXT                          COMMENT '行业发展趋势判断',
    industry_size       VARCHAR(100)                  COMMENT '行业市场规模（如\"全球功率半导体市场约500亿美元\"）',
    industry_growth     VARCHAR(50)                   COMMENT '行业增速（如\"年复合增长率8%\"）',
    -- 技术路线
    tech_route          VARCHAR(200)                  COMMENT '主要技术路线',
    tech_trend          TEXT                          COMMENT '技术发展趋势',
    -- 系统字段
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产业链分析';
```

### 9.2 stock_product_breakdown — 主营产品构成

数据来源：kimi 智能体（同花顺 → 主营构成）

```sql
CREATE TABLE stock_product_breakdown (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL,
    report_date         DATE NOT NULL               COMMENT '报告期',
    -- 产品
    product_name        VARCHAR(100) NOT NULL       COMMENT '产品/业务名称',
    product_category    VARCHAR(50)                  COMMENT '产品分类: DEVICE=器件, MODULE=模块, CHIP=芯片, SERVICE=服务, OTHER=其他',
    -- 财务数据
    revenue             DECIMAL(20,2)               COMMENT '营业收入(元)',
    revenue_ratio       DECIMAL(8,2)  NOT NULL      COMMENT '营收占比(%)',
    gross_margin        DECIMAL(8,2)                COMMENT '毛利率(%)',
    revenue_yoy         DECIMAL(8,2)                COMMENT '营收同比增长(%)',
    -- 竞争力
    market_position     VARCHAR(100)                 COMMENT '市场地位描述（如\"国内第一\"）',
    competitiveness     TEXT                         COMMENT '该产品竞争力分析',
    -- 系统字段
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_product_stock (stock_id),
    INDEX idx_product_date (stock_id, report_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主营产品构成';
```

### 9.3 stock_competitor — 竞品对比

数据来源：kimi 智能体（同花顺 → 同行对比）

```sql
CREATE TABLE stock_competitor (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL               COMMENT '关联 stock.id（主体股票）',
    competitor_name     VARCHAR(100) NOT NULL         COMMENT '竞品公司名称',
    competitor_code     VARCHAR(12)                    COMMENT '竞品股票代码（若上市）',
    competitor_exchange VARCHAR(10)                    COMMENT '竞品上市交易所',
    -- 财务对比
    competitor_market_cap   BIGINT                     COMMENT '竞品总市值(元)',
    competitor_revenue      DECIMAL(20,2)               COMMENT '竞品营业收入(元)',
    competitor_net_profit   DECIMAL(20,2)               COMMENT '竞品净利润(元)',
    competitor_gross_margin DECIMAL(8,2)                COMMENT '竞品毛利率(%)',
    competitor_roe          DECIMAL(8,2)                COMMENT '竞品ROE(%)',
    -- 业务对比
    competitor_main_product VARCHAR(200)                COMMENT '竞品主营产品',
    market_share_note       TEXT                        COMMENT '市占率说明',
    market_share_rank       TINYINT                     COMMENT '市占率排名（1=第一）',
    -- 定性评价
    scarcity                VARCHAR(10)                 COMMENT '稀缺性: HIGH=高, MEDIUM=中, LOW=低',
    scarcity_note           TEXT                        COMMENT '稀缺性判断依据',
    moat                     VARCHAR(10)                COMMENT '护城河: STRONG=强, MEDIUM=中, WEAK=弱',
    moat_note               TEXT                        COMMENT '护城河判断依据',
    advantage                TEXT                        COMMENT '竞品优势',
    disadvantage             TEXT                        COMMENT '竞品劣势',
    -- 对比关系
    compare_date            DATE                        COMMENT '对比数据日期',
    competitor_is_listed    BOOLEAN DEFAULT TRUE        COMMENT '竞品是否上市',
    -- 系统字段
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_competitor_stock (stock_id),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='竞品对比分析';
```

---

## 7. 实体关系图（更新）

```
                    ┌── 市场域 (独立，不关联stock) ──┐
                    │  market_index                   │
                    │  market_aggregate                │
                    └──────────────────────────────────┘

stock (1) ──────< (N) stock_daily
   │
   ├──(1:1)── stock_company              (天眼查)
   ├──(1:1)── stock_quote                (同花顺实时)
   ├──(1:1)── stock_industry_chain       (kimi 智能体) 产业链域
   ├──(1:N)── stock_product_breakdown    (kimi 智能体) 产业链域
   ├──(1:N)── stock_competitor           (kimi 智能体) 产业链域
   ├──(1:N)── stock_income               (同花顺利润表)
   ├──(1:N)── stock_balance              (同花顺资产负债表)
   ├──(1:N)── stock_cashflow             (同花顺现金流量表)
   ├──(1:N)── stock_financial            (同花顺财务指标)
   ├──(1:N)── stock_holder               (同花顺股东户数)
   ├──(1:N)── stock_top_holder           (同花顺十大股东)
   ├──(1:N)── stock_valuation            (同花顺估值)
   ├──(1:N)── stock_money_flow           (同花顺资金流向) ★新增
   ├──(1:N)── stock_chip_structure       (同花顺筹码分布) ★新增
   └──(1:N)── stock_daily_indicator      (本地计算)
```

---

## 8. 表汇总（更新）

| 序号 | 表名 | 中文名 | 数据域 | 数据来源 | 行级估算 |
|------|------|--------|--------|---------|---------|
| 1 | `stock` | 股票基本信息 | 标的基础域 | 同花顺/天眼查 | ~5000 |
| 2 | `stock_company` | 公司工商信息 | 标的基础域 | 天眼查 | ~5000 |
| 3 | `stock_quote` | 最新行情快照 | 行情域 | 同花顺 | ~5000 |
| 4 | `stock_daily` | 日K线 | 行情域 | 同花顺 | ~5000×250×5=625万 |
| 5 | `stock_daily_indicator` | 技术指标 | 行情域 | 本地计算 | 同上 |
| 6 | `market_index` | 市场指数日K | 市场域 | 同花顺 | ~10×250×5=1.25万 |
| 7 | `market_aggregate` | 全市场聚合 | 市场域 | 同花顺 | ~250×5=1250 |
| 8 | `stock_money_flow` | 个股资金流向 | 资金流域 | 同花顺 | ~5000×250=125万 |
| 9 | `stock_chip_structure` | 筹码分布 | 筹码域 | 同花顺 | ~5000×250=125万 |
| 10 | `stock_industry_chain` | 产业链分析 | 产业链域 | kimi 智能体 | ~5000 |
| 11 | `stock_product_breakdown` | 主营产品构成 | 产业链域 | kimi 智能体 | ~5000×5=2.5万 |
| 12 | `stock_competitor` | 竞品对比 | 产业链域 | kimi 智能体 | ~5000×5=2.5万 |
| 13 | `stock_income` | 利润表 | 财务域 | 同花顺 | ~5000×5×4=10万 |
| 14 | `stock_balance` | 资产负债表 | 财务域 | 同花顺 | 同上 |
| 15 | `stock_cashflow` | 现金流量表 | 财务域 | 同花顺 | 同上 |
| 16 | `stock_financial` | 财务指标 | 财务域 | 同花顺 | 同上 |
| 17 | `stock_holder` | 股东集中度 | 股东域 | 同花顺 | ~5000×5×4=10万 |
| 18 | `stock_top_holder` | 十大股东 | 股东域 | 同花顺 | ~5000×5×10=25万 |
| 19 | `stock_valuation` | 估值指标 | 估值域 | 同花顺 | ~5000×250×5=625万 |

**总计 19 张表，全量约 1560 万行**。按单只股票（5年历史）约 3100 行。

---

## 1. 标的基础域

### 1.1 stock — 股票基本信息

数据来源：同花顺个股概要页 + 天眼查工商信息

```sql
CREATE TABLE stock (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(12)   NOT NULL UNIQUE   COMMENT '股票代码，如 600519.SH',
    name_cn         VARCHAR(50)   NOT NULL          COMMENT '中文简称',
    name_en         VARCHAR(100)                     COMMENT '英文名称',
    exchange        VARCHAR(10)   NOT NULL          COMMENT '交易所: SSE=上交所, SZSE=深交所, SEHK=港交所, NASD=纳斯达克, NYSE=纽交所',
    currency        VARCHAR(5)    DEFAULT 'CNY'     COMMENT '交易货币: CNY, HKD, USD',
    lot_size        INT           DEFAULT 100       COMMENT '每手股数: A股=100, 港股不固定, 美股=1',
    -- 交易状态 (同花顺可查)
    listing_status  VARCHAR(10)   DEFAULT 'NORMAL'  COMMENT '上市状态: NORMAL=正常, SUSPENDED=停牌, DELISTED=退市',
    listing_date    DATE                             COMMENT '上市日期',
    delisting_date  DATE                             COMMENT '退市日期',
    -- 股本结构 (同花顺可查)
    total_shares           BIGINT                    COMMENT '总股本(股)',
    circulating_shares     BIGINT                    COMMENT '流通股本(股)',
    restricted_shares      BIGINT                    COMMENT '限售股本(股)',
    -- 行业分类 (同花顺行业分类)
    industry_l1     VARCHAR(50)                      COMMENT '申万一级行业',
    industry_l2     VARCHAR(50)                      COMMENT '申万二级行业',
    industry_l3     VARCHAR(50)                      COMMENT '申万三级行业',
    -- 概念板块 (同花顺概念板块)
    concept_tags    VARCHAR(500)                     COMMENT '所属概念板块, 逗号分隔',
    -- 系统字段
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_stock_code (code),
    INDEX idx_stock_industry_l1 (industry_l1),
    INDEX idx_stock_listing_status (listing_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票基本信息';
```

**字段设计依据**：

| 字段 | AllTick 模板字段 | 同花顺 / 天眼查可查 |
|------|:--:|:--:|
| code | `symbol` | 同花顺 |
| name_cn | `name_cn` | 同花顺 |
| name_en | `name_en` | 同花顺 |
| exchange | `exchange` | 同花顺 |
| currency | `currency` | 同花顺 |
| lot_size | `lot_size` | 同花顺 |
| listing_status | — | 同花顺 |
| listing_date | — | 同花顺/天眼查 |
| total_shares | `total_shares` | 同花顺 |
| circulating_shares | `circulating_shares` | 同花顺 |
| restricted_shares | — | 同花顺 |
| industry_l1/l2/l3 | `board`(仅大类) | 同花顺/天眼查 |
| concept_tags | — | 同花顺 |

### 1.2 stock_company — 公司工商信息

数据来源：天眼查

```sql
CREATE TABLE stock_company (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL UNIQUE        COMMENT '关联 stock.id',
    -- 工商信息 (天眼查)
    full_name_cn        VARCHAR(200)                  COMMENT '公司全称',
    short_name_cn       VARCHAR(50)                   COMMENT '公司简称',
    english_name        VARCHAR(200)                  COMMENT '英文全称',
    legal_representative VARCHAR(50)                  COMMENT '法定代表人',
    actual_controller   VARCHAR(50)                   COMMENT '实际控制人',
    established_date    DATE                          COMMENT '成立日期',
    registered_capital  DECIMAL(20,2)                 COMMENT '注册资本(万元)',
    paid_in_capital     DECIMAL(20,2)                 COMMENT '实缴资本(万元)',
    company_type        VARCHAR(50)                   COMMENT '公司类型: 股份有限公司等',
    registration_no     VARCHAR(50)                   COMMENT '统一社会信用代码',
    registered_address  VARCHAR(200)                  COMMENT '注册地址',
    office_address      VARCHAR(200)                  COMMENT '办公地址',
    business_scope      TEXT                          COMMENT '经营范围',
    employee_count      INT                           COMMENT '员工人数',
    website             VARCHAR(200)                  COMMENT '公司官网',
    telephone           VARCHAR(50)                   COMMENT '联系电话',
    email               VARCHAR(100)                  COMMENT '联系邮箱',
    -- 上市板块信息
    ipo_price           DECIMAL(10,2)                 COMMENT '发行价格',
    ipo_shares          BIGINT                        COMMENT '发行数量(股)',
    ipo_amount          DECIMAL(20,2)                 COMMENT '募资金额(万元)',
    sponsor             VARCHAR(100)                  COMMENT '保荐机构',
    -- 系统字段
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司工商信息(天眼查)';
```

---

## 2. 行情域

### 2.1 stock_quote — 最新行情快照

数据来源：同花顺个股行情

```sql
CREATE TABLE stock_quote (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id        BIGINT NOT NULL UNIQUE        COMMENT '关联 stock.id',
    -- 价格
    latest_price    DECIMAL(12,3)                 COMMENT '最新价',
    open_price      DECIMAL(12,3)                 COMMENT '今日开盘价',
    high_price      DECIMAL(12,3)                 COMMENT '今日最高价',
    low_price       DECIMAL(12,3)                 COMMENT '今日最低价',
    prev_close      DECIMAL(12,3)                 COMMENT '昨日收盘价',
    -- 涨跌
    change_amount   DECIMAL(12,3)                 COMMENT '涨跌额 = latest - prev_close',
    change_percent  DECIMAL(10,2)                 COMMENT '涨跌幅(%)',
    -- 振幅
    amplitude       DECIMAL(10,2)                 COMMENT '振幅(%) = (high-low)/prev_close*100',
    -- 成交 (基于 AllTick trade-tick 字段推断)
    volume          BIGINT                        COMMENT '成交量(股)',
    turnover        DECIMAL(20,2)                 COMMENT '成交额(元)',
    turnover_rate   DECIMAL(10,2)                 COMMENT '换手率(%)',
    -- 市值
    total_market_cap       BIGINT                 COMMENT '总市值(元)',
    circulating_market_cap BIGINT                 COMMENT '流通市值(元)',
    -- 涨停/跌停价 (A股特有)
    limit_up_price  DECIMAL(12,3)                 COMMENT '涨停价',
    limit_down_price DECIMAL(12,3)                COMMENT '跌停价',
    -- 快照时间
    quote_time      TIMESTAMP                     COMMENT '行情刷新时间',
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='最新行情快照';
```

**AllTick 模板参照**：

| 本表字段 | AllTick `/trade-tick` 字段 |
|----------|---------------------------|
| latest_price | `price` |
| volume | `volume` |
| turnover | `turnover` |
| quote_time | `tick_time`(epoch ms) |

> AllTick `/trade-tick` 只覆盖 price/volume/turnover/trade_direction 4个字段。open/high/low/prev_close/amplitude/turnover_rate/market_cap 等在同花顺个股页面上是标配字段，本模型一并纳入。

### 2.2 stock_daily — 日K线

数据来源：同花顺历史K线

```sql
CREATE TABLE stock_daily (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id        BIGINT NOT NULL               COMMENT '关联 stock.id',
    trade_date      DATE NOT NULL                  COMMENT '交易日期',
    -- OHLCV (基于 AllTick kline 字段)
    open_price      DECIMAL(12,3) NOT NULL         COMMENT '开盘价',
    close_price     DECIMAL(12,3) NOT NULL         COMMENT '收盘价',
    high_price      DECIMAL(12,3) NOT NULL         COMMENT '最高价',
    low_price       DECIMAL(12,3) NOT NULL         COMMENT '最低价',
    volume          BIGINT NOT NULL                COMMENT '成交量(股)',
    turnover        DECIMAL(20,2) NOT NULL         COMMENT '成交额(元)',
    -- 衍生字段 (同花顺K线标配)
    change_percent  DECIMAL(10,2)                  COMMENT '涨跌幅(%)',
    change_amount   DECIMAL(12,3)                  COMMENT '涨跌额',
    turnover_rate   DECIMAL(10,2)                  COMMENT '换手率(%)',
    amplitude       DECIMAL(10,2)                  COMMENT '振幅(%)',

    UNIQUE KEY uk_stock_date (stock_id, trade_date),
    INDEX idx_stock_daily_date (stock_id, trade_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日K线数据';
```

**AllTick 模板参照**：

| 本表字段 | AllTick `/kline` 字段 |
|----------|----------------------|
| trade_date | `timestamp`(epoch s) |
| open_price | `open_price` |
| close_price | `close_price` |
| high_price | `high_price` |
| low_price | `low_price` |
| volume | `volume` |
| turnover | `turnover` |

> AllTick `/kline` 只返回 7 个字段。change_percent/change_amount/turnover_rate/amplitude 在同花顺K线导出中是标配，本模型一并纳入。

### 2.3 stock_daily_indicator — 日K线技术指标

数据来源：本地计算（基于 stock_daily 的 OHLCV 算出），或同花顺直接导出

```sql
CREATE TABLE stock_daily_indicator (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id        BIGINT NOT NULL,
    trade_date      DATE NOT NULL,
    -- 均线 (基于 kline close_price 算出)
    ma5             DECIMAL(12,3),
    ma10            DECIMAL(12,3),
    ma20            DECIMAL(12,3),
    ma60            DECIMAL(12,3),
    ma120           DECIMAL(12,3),
    ma250           DECIMAL(12,3),
    -- MACD (12, 26, 9)
    macd_dif        DECIMAL(12,4),
    macd_dea        DECIMAL(12,4),
    macd_hist       DECIMAL(12,4),
    -- RSI(14)
    rsi             DECIMAL(10,2),
    -- KDJ(9,3,3)
    kdj_k           DECIMAL(10,2),
    kdj_d           DECIMAL(10,2),
    kdj_j           DECIMAL(10,2),
    -- 布林带(20, 2)
    boll_upper      DECIMAL(12,3),
    boll_mid        DECIMAL(12,3),
    boll_lower      DECIMAL(12,3),
    -- 成交量均线
    volume_ma5      BIGINT,
    volume_ma20     BIGINT,

    UNIQUE KEY uk_indicator_date (stock_id, trade_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日K线技术指标(本地计算)';
```

> 此表数据从 stock_daily 的 OHLCV 纯数学计算得出，不依赖外部数据源。属于基础域而非分析域，因为它是后续信号检测等模块的输入。

---

## 3. 财务域

### 3.1 stock_income — 利润表

数据来源：同花顺财务分析 → 利润表

```sql
CREATE TABLE stock_income (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL,
    report_date         DATE NOT NULL              COMMENT '报告期截止日',
    report_type         VARCHAR(10) NOT NULL       COMMENT '报告类型: Q1=一季报, Q2=中报, Q3=三季报, Q4=年报',
    -- 营业总收入
    total_revenue       DECIMAL(20,2)              COMMENT '营业总收入(元)',
    revenue_yoy         DECIMAL(10,2)              COMMENT '营业收入同比增长率(%)',
    -- 营业成本
    operating_cost      DECIMAL(20,2)              COMMENT '营业总成本(元)',
    -- 利润
    operating_profit    DECIMAL(20,2)              COMMENT '营业利润(元)',
    total_profit        DECIMAL(20,2)              COMMENT '利润总额(元)',
    income_tax          DECIMAL(20,2)              COMMENT '所得税费用(元)',
    parent_net_profit   DECIMAL(20,2)              COMMENT '归母净利润(元)',
    net_profit_yoy      DECIMAL(10,2)              COMMENT '归母净利润同比增长率(%)',
    -- 每股
    basic_eps           DECIMAL(12,4)              COMMENT '基本每股收益(元/股)',
    diluted_eps         DECIMAL(12,4)              COMMENT '稀释每股收益(元/股)',

    UNIQUE KEY uk_income_report (stock_id, report_date, report_type),
    INDEX idx_income_date (stock_id, report_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='利润表';
```

### 3.2 stock_balance — 资产负债表

数据来源：同花顺财务分析 → 资产负债表

```sql
CREATE TABLE stock_balance (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id              BIGINT NOT NULL,
    report_date           DATE NOT NULL,
    report_type           VARCHAR(10) NOT NULL,
    -- 资产
    total_assets          DECIMAL(20,2)            COMMENT '资产总计(元)',
    current_assets        DECIMAL(20,2)            COMMENT '流动资产合计(元)',
    fixed_assets          DECIMAL(20,2)            COMMENT '固定资产(元)',
    cash_equivalents      DECIMAL(20,2)            COMMENT '货币资金(元)',
    accounts_receivable   DECIMAL(20,2)            COMMENT '应收账款(元)',
    inventory             DECIMAL(20,2)            COMMENT '存货(元)',
    goodwill              DECIMAL(20,2)            COMMENT '商誉(元)',
    -- 负债
    total_liabilities     DECIMAL(20,2)            COMMENT '负债合计(元)',
    current_liabilities   DECIMAL(20,2)            COMMENT '流动负债合计(元)',
    noncurrent_liabilities DECIMAL(20,2)           COMMENT '非流动负债合计(元)',
    -- 权益
    shareholder_equity    DECIMAL(20,2)            COMMENT '归属于母公司股东权益合计(元)',

    UNIQUE KEY uk_balance_report (stock_id, report_date, report_type),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产负债表';
```

### 3.3 stock_cashflow — 现金流量表

数据来源：同花顺财务分析 → 现金流量表

```sql
CREATE TABLE stock_cashflow (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id              BIGINT NOT NULL,
    report_date           DATE NOT NULL,
    report_type           VARCHAR(10) NOT NULL,
    -- 经营活动
    operating_cf          DECIMAL(20,2)            COMMENT '经营活动现金流量净额(元)',
    operating_cf_in       DECIMAL(20,2)            COMMENT '经营活动现金流入(元)',
    operating_cf_out      DECIMAL(20,2)            COMMENT '经营活动现金流出(元)',
    -- 投资活动
    investing_cf          DECIMAL(20,2)            COMMENT '投资活动现金流量净额(元)',
    -- 筹资活动
    financing_cf          DECIMAL(20,2)            COMMENT '筹资活动现金流量净额(元)',
    -- 汇总
    net_cf                DECIMAL(20,2)            COMMENT '现金及现金等价物净增加额(元)',
    free_cf               DECIMAL(20,2)            COMMENT '自由现金流 = 经营CF + 投资CF',

    UNIQUE KEY uk_cashflow_report (stock_id, report_date, report_type),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='现金流量表';
```

### 3.4 stock_financial — 财务指标汇总

数据来源：同花顺财务分析 → 财务指标

```sql
CREATE TABLE stock_financial (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL,
    report_date         DATE NOT NULL,
    report_type         VARCHAR(10) NOT NULL,
    -- 盈利能力 (同花顺可查)
    eps                 DECIMAL(12,4)              COMMENT '基本每股收益',
    eps_yoy             DECIMAL(10,2)              COMMENT '每股收益同比增长率(%)',
    roe                 DECIMAL(10,2)              COMMENT '净资产收益率ROE(%)',
    roe_diluted         DECIMAL(10,2)              COMMENT '净资产收益率(扣非/稀释)(%)',
    roa                 DECIMAL(10,2)              COMMENT '总资产收益率ROA(%)',
    gross_margin        DECIMAL(10,2)              COMMENT '销售毛利率(%)',
    net_profit_margin   DECIMAL(10,2)              COMMENT '销售净利率(%)',
    -- 营运能力
    inventory_turnover  DECIMAL(10,2)              COMMENT '存货周转率(次)',
    receivables_turnover DECIMAL(10,2)             COMMENT '应收账款周转率(次)',
    total_asset_turnover DECIMAL(10,2)             COMMENT '总资产周转率(次)',
    -- 偿债能力
    debt_ratio          DECIMAL(10,2)              COMMENT '资产负债率(%)',
    current_ratio       DECIMAL(10,2)              COMMENT '流动比率',
    quick_ratio         DECIMAL(10,2)              COMMENT '速动比率',
    equity_ratio        DECIMAL(10,2)              COMMENT '产权比率(%)',
    -- 成长能力 (同比)
    revenue_yoy         DECIMAL(10,2)              COMMENT '营业收入同比增长率(%)',
    profit_yoy          DECIMAL(10,2)              COMMENT '归母净利润同比增长率(%)',
    -- 每股指标
    bvps                DECIMAL(12,4)              COMMENT '每股净资产',
    ocfps               DECIMAL(12,4)              COMMENT '每股经营现金流',
    undistributed_ps    DECIMAL(12,4)              COMMENT '每股未分配利润',
    -- 期间费用率
    period_expense_ratio DECIMAL(10,2)             COMMENT '期间费用率(%) = (销售+管理+财务费用)/营业收入',

    UNIQUE KEY uk_financial_report (stock_id, report_date, report_type),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财务指标汇总(同花顺)';
```

**AllTick 模板参照**：

| AllTick static_info 字段 | 本域对应字段 |
|--------------------------|-------------|
| `eps` | `stock_financial.eps` (最新一期) |
| `eps_ttm` | 计算字段（需近4个季度累加） |
| `bps` | `stock_financial.bvps` |
| `dividend_yield` | `stock_valuation.dividend_yield` |

> AllTick 只提供 4 个财务字段的**最新值**。同花顺可查完整历史时间序列（ROE/毛利率/负债率等 20+ 指标），所以本模型大幅扩展。

---

## 4. 股东域

### 4.1 stock_holder — 股东户数与集中度

数据来源：同花顺股东研究

```sql
CREATE TABLE stock_holder (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL,
    end_date            DATE NOT NULL             COMMENT '统计截止日期（财报期末）',
    -- 股东户数
    holder_total_num    INT                        COMMENT '股东总户数',
    holder_num_change   INT                        COMMENT '股东户数变化（较上期）',
    holder_num_ratio    DECIMAL(10,2)              COMMENT '股东户数变化率(%)',
    -- 持股集中度
    avg_free_shares     BIGINT                     COMMENT '户均持股(流通股)',
    avg_hold_amt        BIGINT                     COMMENT '户均持股市值(元)',
    -- 机构持股
    institution_count   INT                        COMMENT '机构持股家数',
    institution_ratio   DECIMAL(10,2)              COMMENT '机构持股占流通股比例(%)',
    -- 前十大股东合计
    top10_hold_ratio    DECIMAL(10,2)              COMMENT '前十大股东持股比例合计(%)',
    -- 高管持股
    executive_hold_ratio DECIMAL(10,2)             COMMENT '高管持股占流通股比例(%)',

    UNIQUE KEY uk_holder_date (stock_id, end_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股东户数与集中度';
```

### 4.2 stock_top_holder — 前十大股东明细

数据来源：同花顺股东研究 → 十大股东

```sql
CREATE TABLE stock_top_holder (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL,
    end_date            DATE NOT NULL,
    rank_no             TINYINT NOT NULL          COMMENT '排名: 1-10',
    holder_name         VARCHAR(200) NOT NULL     COMMENT '股东名称',
    holder_type         VARCHAR(20)               COMMENT '股东类型: 机构/个人/香港中央结算/国家队等',
    hold_shares         BIGINT                    COMMENT '持股数量(股)',
    hold_ratio          DECIMAL(10,4)             COMMENT '持股比例(%)',
    change_shares       BIGINT                    COMMENT '较上期增减(股)',
    is_new              BOOLEAN DEFAULT FALSE     COMMENT '是否新进',

    UNIQUE KEY uk_top_holder (stock_id, end_date, rank_no),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='前十大股东明细';
```

---

## 5. 估值域

### 5.1 stock_valuation — 估值指标

数据来源：同花顺价值分析 / 同花顺估值页面

```sql
CREATE TABLE stock_valuation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id            BIGINT NOT NULL,
    report_date         DATE NOT NULL              COMMENT '估值日期',
    -- 相对估值 (同花顺可查)
    pe_ttm              DECIMAL(10,2)              COMMENT '市盈率(TTM)',
    pe_lyr              DECIMAL(10,2)              COMMENT '市盈率(静态/上年)',
    pe_forward          DECIMAL(10,2)              COMMENT '市盈率(动态/预期)',
    pb                  DECIMAL(10,2)              COMMENT '市净率',
    ps_ttm              DECIMAL(10,2)              COMMENT '市销率(TTM)',
    pcf_ttm             DECIMAL(10,2)              COMMENT '市现率(TTM)',
    peg                 DECIMAL(10,2)              COMMENT 'PEG = PE / 净利润增长率',
    -- 分红
    dividend_yield      DECIMAL(10,2)              COMMENT '股息率(%)',
    dividend_amount     DECIMAL(10,3)              COMMENT '每股股利(元)',
    -- 估值分位 (需要行业均值，kimi可从同花顺获取)
    pe_percentile       DECIMAL(10,2)              COMMENT 'PE历史分位(%)-近5年',
    pb_percentile       DECIMAL(10,2)              COMMENT 'PB历史分位(%)-近5年',
    -- 行业对比
    industry_pe_avg     DECIMAL(10,2)              COMMENT '行业平均PE',
    industry_pb_avg     DECIMAL(10,2)              COMMENT '行业平均PB',
    -- 市值
    total_market_cap    BIGINT                     COMMENT '总市值(元)',
    circulating_market_cap BIGINT                  COMMENT '流通市值(元)',

    UNIQUE KEY uk_valuation_date (stock_id, report_date),
    FOREIGN KEY (stock_id) REFERENCES stock(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='估值指标';
```

**AllTick 模板参照**：

| AllTick static_info 字段 | 本表字段 |
|--------------------------|---------|
| `eps_ttm` | 用于计算 `pe_ttm = latest_price / eps_ttm` |
| `dividend_yield` | `dividend_yield` |
| `bps` | 用于计算 `pb = latest_price / bps` |

> AllTick 只给最新一个时点的值。同花顺可以查到**历史估值走势 + 行业分位 + 行业均值**。

---

## 6. 实体关系图

```
市场域 (独立，不关联stock)
market_index (指数日K)    market_aggregate (全市场聚合)

stock (1) ──────< (N) stock_daily
   │
   ├──(1:1)── stock_company              (天眼查)
   ├──(1:1)── stock_quote                (同花顺实时)
   ├──(1:N)── stock_income               (同花顺利润表)
   ├──(1:N)── stock_balance              (同花顺资产负债表)
   ├──(1:N)── stock_cashflow             (同花顺现金流量表)
   ├──(1:N)── stock_financial            (同花顺财务指标)
   ├──(1:N)── stock_holder               (同花顺股东户数)
   ├──(1:N)── stock_top_holder           (同花顺十大股东)
   ├──(1:N)── stock_valuation            (同花顺估值)
   ├──(1:N)── stock_money_flow           (同花顺资金流向)
   ├──(1:N)── stock_chip_structure       (同花顺筹码分布)
   ├──(1:1)── stock_industry_chain       (kimi 智能体)
   ├──(1:N)── stock_product_breakdown    (kimi 智能体)
   ├──(1:N)── stock_competitor           (kimi 智能体)
   └──(1:N)── stock_daily_indicator      (本地计算)
```

---

## 7. 数据填充流程（kimi 智能体）

```
用户输入: "分析 600519 贵州茅台"
    │
    ▼
kimi 智能体读取本模型
    │
    ├─ Phase 1: 标的识别
    │   ├─ 同花顺 → stock (code/name/exchange/industry/shares)
    │   └─ 天眼查 → stock_company (工商信息)
    │
    ├─ Phase 2: 个股行情
    │   ├─ 同花顺 → stock_quote (最新行情)
    │   └─ 同花顺 → stock_daily (历史K线, 默认5年)
    │
    ├─ Phase 3: 市场数据
    │   ├─ 同花顺 → market_index (上证/深证/创业板/科创50)
    │   └─ 同花顺 → market_aggregate (全市场成交/涨跌/资金)
    │
    ├─ Phase 4: 财务数据
    │   ├─ 同花顺 → stock_income (近5年每季度)
    │   ├─ 同花顺 → stock_balance
    │   ├─ 同花顺 → stock_cashflow
    │   └─ 同花顺 → stock_financial (财务指标)
    │
    ├─ Phase 5: 资金与筹码
    │   ├─ 同花顺 → stock_money_flow (资金流向)
    │   └─ 同花顺 → stock_chip_structure (筹码分布)
    │
    ├─ Phase 6: 股东与估值
    │   ├─ 同花顺 → stock_holder + stock_top_holder
    │   └─ 同花顺 → stock_valuation
    │
    ├─ Phase 7: 产业链 (kimi 智能体深度分析)
    │   ├─ 同花顺+天眼查 → stock_industry_chain
    │   ├─ 同花顺 → stock_product_breakdown (主营构成)
    │   └─ 同花顺 → stock_competitor (竞品对比)
    │
    └─ Phase 8: 本地计算
        └─ OHLCV → stock_daily_indicator (MA/MACD/RSI/KDJ/BOLL)
```

---

## 8. 表汇总

| 序号 | 表名 | 中文名 | 数据域 | 数据来源 | 行级估算 |
|------|------|--------|--------|---------|---------|
| 1 | `stock` | 股票基本信息 | 标的基础域 | 同花顺/天眼查 | ~5000 (全A) |
| 2 | `stock_company` | 公司工商信息 | 标的基础域 | 天眼查 | ~5000 |
| 3 | `stock_quote` | 最新行情快照 | 行情域 | 同花顺 | ~5000 |
| 4 | `stock_daily` | 日K线 | 行情域 | 同花顺 | ~5000×250×5=625万 |
| 5 | `stock_daily_indicator` | 技术指标 | 行情域 | 本地计算 | 同上 |
| 6 | `market_index` | 市场指数日K | 市场域 | 同花顺 | ~10×250×5=1.25万 |
| 7 | `market_aggregate` | 全市场聚合 | 市场域 | 同花顺 | ~250×5=1250 |
| 8 | `stock_money_flow` | 个股资金流向 | 资金流域 | 同花顺 | ~5000×250=125万 |
| 9 | `stock_chip_structure` | 筹码分布 | 筹码域 | 同花顺 | ~5000×250=125万 |
| 10 | `stock_industry_chain` | 产业链分析 | 产业链域 | kimi 智能体 | ~5000 |
| 11 | `stock_product_breakdown` | 主营产品构成 | 产业链域 | kimi 智能体 | ~5000×5=2.5万 |
| 12 | `stock_competitor` | 竞品对比 | 产业链域 | kimi 智能体 | ~5000×5=2.5万 |
| 13 | `stock_income` | 利润表 | 财务域 | 同花顺 | ~5000×5×4=10万 |
| 14 | `stock_balance` | 资产负债表 | 财务域 | 同花顺 | 同上 |
| 15 | `stock_cashflow` | 现金流量表 | 财务域 | 同花顺 | 同上 |
| 16 | `stock_financial` | 财务指标 | 财务域 | 同花顺 | 同上 |
| 17 | `stock_holder` | 股东集中度 | 股东域 | 同花顺 | ~5000×5×4=10万 |
| 18 | `stock_top_holder` | 十大股东 | 股东域 | 同花顺 | ~5000×5×10=25万 |
| 19 | `stock_valuation` | 估值指标 | 估值域 | 同花顺 | ~5000×250×5=625万 |

**总计 19 张表，全量约 1560 万行**。按单只股票（5年历史）约 3100 行。

> 以 MySQL 存储，单表百万~千万级别查询 < 100ms (建立索引后)。
