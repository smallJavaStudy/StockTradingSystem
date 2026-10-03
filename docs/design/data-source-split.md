# 双 Agent 数据源分组方案

**版本**: v1.0
**日期**: 2026-07-08
**状态**: ✅ 已验证（扬杰科技 300373 / 富满微 300671）

---

## 1. 概述

本文档定义 StockTradingSystem 双 Agent 数据源架构中，19 张数据表在 **DeepSeek Flash** 和 **Kimi** 之间的分组边界。

### 两个 Agent 的定位

| Agent | 模型 | 数据接入 | 成本 | 定位 |
|-------|------|---------|------|------|
| **DeepSeekDataAgent** | `deepseek-chat` | 无外部数据源 | 免费/廉价 | 公开知识层：定性分析、公司概况、产业链定位 |
| **KimiDataAgent** | `kimi-for-coding` | 同花顺 + 天眼查 | 付费 (Andante) | 精确数据层：实时行情、财务数字、资金流向、筹码分布 |

### 分组原则

- **DeepSeek Flash 能拿的**：从训练数据（Internet 公开知识）中可直接回答的数据——公司基本信息、行业分类、产业链定性描述、竞品定性对比、产品概述
- **Kimi 必须拿的**：需要从同花顺/天眼查数据库获取的精确结构化数据——精确财务数字、实时行情、K 线 OHLCV、资金流向、筹码分布、最新股东

---

## 2. 分组总表

| # | 表名 | Group | 判定 | 原因 |
|---|------|:-----:|------|------|
| 1 | stock | **混合** | DS↔Kimi | 代码/行业/概念→DS；股本数量→Kimi |
| 2 | stock_company | **混合** | DS↔Kimi | 法人/实控人/经营范围/商业模式→DS；精确员工数/注册资本→Kimi |
| 3 | stock_quote | **Kimi** | Kimi only | 实时行情，DS 训练数据无当前价格 |
| 4 | stock_daily | **Kimi** | Kimi only | 日 K 线 OHLCV，DS 无精确历史行情 |
| 5 | stock_daily_indicator | **本地** | 本地计算 | 基于 stock_daily 的纯数学计算 |
| 6 | market_index | **Kimi** | Kimi only | 指数精确 K 线，同花顺数据 |
| 7 | market_aggregate | **Kimi** | Kimi only | 全市场实时聚合统计 |
| 8 | stock_money_flow | **Kimi** | Kimi only | 精确资金流向，需同花顺接口 |
| 9 | stock_chip_structure | **Kimi** | Kimi only | 精确筹码分布，需同花顺数据 |
| 10 | stock_industry_chain | **DS** ✅ | DS 全量 | 产业链定位、上下游、政策、趋势——全是定性分析 |
| 11 | stock_product_breakdown | **混合** | DS↔Kimi | 产品名/分类/竞争力→DS；精确营收/毛利率→Kimi |
| 12 | stock_competitor | **混合** | DS↔Kimi | 竞品定性评价/护城河→DS；竞品精确市值/营收→Kimi |
| 13 | stock_income | **Kimi** | Kimi only | 精确到元的利润表数据 |
| 14 | stock_balance | **Kimi** | Kimi only | 精确资产负债表 |
| 15 | stock_cashflow | **Kimi** | Kimi only | 精确现金流量表 |
| 16 | stock_financial | **Kimi** | Kimi only | 精确财务指标（ROE/毛利率等） |
| 17 | stock_holder | **Kimi** | Kimi only | 精确股东户数/集中度 |
| 18 | stock_top_holder | **Kimi** | Kimi only | 最新十大股东明细 |
| 19 | stock_valuation | **Kimi** | Kimi only | 精确估值指标（PE/PB/分位） |

### 汇总统计

| 分组 | 表数 | 占比 |
|------|:---:|:---:|
| **DeepSeek Flash 全量** | 1 (stock_industry_chain) | 5.3% |
| **混合（DS + Kimi 各取所需）** | 4 (stock, stock_company, stock_product_breakdown, stock_competitor) | 21.1% |
| **Kimi only** | 13 | 68.4% |
| **本地计算** | 1 (stock_daily_indicator) | 5.3% |

---

## 3. 字段级拆分

### 3.1 stock — 股票基本信息（混合表）

| 字段 | 来源 | 说明 |
|------|:---:|------|
| code, name_cn, name_en | **DS** | 公开信息，DS 训练数据可覆盖 |
| exchange, currency, lot_size | **DS** | 公开信息 |
| listing_status, listing_date, delisting_date | **DS** | 公开信息 |
| industry_l1, industry_l2, industry_l3 | **DS** | 申万行业分类，公开标准 |
| concept_tags | **DS** | 概念板块，公开信息 |
| total_shares, circulating_shares, restricted_shares | **Kimi** | 精确股本数量有时效性变化，需同花顺 |

### 3.2 stock_company — 公司工商信息（混合表）

| 字段 | 来源 | 说明 |
|------|:---:|------|
| full_name_cn, short_name_cn, english_name | **DS** | 公开工商信息 |
| legal_representative, actual_controller | **DS** | 公开信息，媒体广泛报道 |
| established_date, company_type | **DS** | 公开工商信息 |
| business_scope, registered_address, office_address | **DS** | 公开信息 |
| website, business_model 描述 | **DS** | 公开可查 |
| registered_capital, paid_in_capital | **Kimi** | 精确工商登记数据 |
| employee_count | **Kimi** | 精确员工数有时效性 |
| registration_no, telephone, email | **Kimi** | 精确工商/联系信息 |
| ipo_price, ipo_shares, ipo_amount, sponsor | **Kimi** | 精确 IPO 数据 |

### 3.3 stock_quote — 最新行情快照（Kimi only）

> **全字段 Kimi**。实时行情（最新价/开盘/最高/最低/成交量/市值等）DS 训练数据无法提供。

### 3.4 stock_daily — 日K线（Kimi only）

> **全字段 Kimi**。日级别 OHLCV 精确历史数据，DS 无此能力。

### 3.5 stock_daily_indicator — 技术指标（本地计算）

> **本地计算**。基于 stock_daily 的 OHLCV 纯数学计算（MA/MACD/RSI/KDJ/BOLL），不依赖任何外部数据源。

### 3.6 market_index — 市场指数日K（Kimi only）

> **全字段 Kimi**。

### 3.7 market_aggregate — 全市场聚合（Kimi only）

> **全字段 Kimi**。

### 3.8 stock_money_flow — 个股资金流向（Kimi only）

> **全字段 Kimi**。主力/超大单/大单/中单/小单净流入及占比，需同花顺资金流向数据。

### 3.9 stock_chip_structure — 筹码分布（Kimi only）

> **全字段 Kimi**。筹码集中度/平均成本/获利盘比例/密集区，需同花顺筹码分布数据。

### 3.10 stock_industry_chain — 产业链分析（DS 全量 ✅）

| 字段 | 来源 | 说明 |
|------|:---:|------|
| core_product | **DS** | 公开知识：公司主营产品描述 |
| industry_position | **DS** | 公开知识：上游/中游/下游/IDM |
| upstream | **DS** | 公开知识：原材料/供应商描述 |
| downstream | **DS** | 公开知识：应用领域/客户描述 |
| key_customers | **DS** | 公开知识：知名下游企业 |
| key_suppliers | **DS** | 公开知识：主要供应商 |
| lifecycle_stage | **DS** | 公开知识：行业生命周期判断 |
| lifecycle_note | **DS** | 公开知识：判断依据 |
| policy_impact | **DS** | 公开知识：产业政策分析 |
| policy_detail | **DS** | 公开知识：具体政策文件 |
| industry_trend | **DS** | 公开知识：行业趋势判断 |
| industry_size | **DS** | 公开知识：市场规模（近似值） |
| industry_growth | **DS** | 公开知识：行业增速（近似值） |
| tech_route | **DS** | 公开知识：技术路线 |
| tech_trend | **DS** | 公开知识：技术趋势 |

> **此表是 DeepSeek Flash 的 sweet spot**——全部是定性分析字段，DS 的通用知识完全胜任。

### 3.11 stock_product_breakdown — 主营产品构成（混合表）

| 字段 | 来源 | 说明 |
|------|:---:|------|
| product_name | **DS** | 公开知识：产品名称 |
| product_category | **DS** | 公开知识：器件/模块/芯片/服务分类 |
| market_position, competitiveness | **DS** | 定性分析 |
| revenue, revenue_ratio（精确） | **Kimi** | 精确营收数字，同花顺主营构成 |
| gross_margin（精确） | **Kimi** | 精确毛利率，同花顺财务数据 |
| revenue_yoy（精确） | **Kimi** | 精确同比增速 |

> DeepSeek 可提供「营收占比约 50%」「毛利率约 30-35%」等近似值，精度足够用于初步分析。

### 3.12 stock_competitor — 竞品对比（混合表）

| 字段 | 来源 | 说明 |
|------|:---:|------|
| competitor_name, competitor_code | **DS** | 公开知识：竞品公司信息 |
| competitor_exchange | **DS** | 公开信息 |
| competitor_main_product | **DS** | 公开知识 |
| market_share_note, market_share_rank | **DS** | 定性分析 |
| scarcity, scarcity_note | **DS** | 定性评价 |
| moat, moat_note | **DS** | 护城河定性评价 |
| advantage, disadvantage | **DS** | 优劣势定性分析 |
| competitor_market_cap（精确） | **Kimi** | 精确市值 |
| competitor_revenue, competitor_net_profit | **Kimi** | 精确财务数字 |
| competitor_gross_margin, competitor_roe | **Kimi** | 精确财务指标 |

### 3.13-3.19 财务/股东/估值表（Kimi only）

> **全字段 Kimi**。这些表全部依赖同花顺精确结构化的财务数据、股东数据和估值数据，DeepSeek Flash 的训练数据无法提供到所需的精度。

---

## 4. 验证结果

### 4.1 测试标的

| 标的 | 代码 | 行业 | 测试日期 |
|------|------|------|----------|
| 扬杰科技 | 300373 | 电子/半导体/分立器件 | 2026-07-08 |
| 富满微 | 300671 | 电子/半导体/模拟芯片设计 | 2026-07-08 |

### 4.2 扬杰科技 (300373) DeepSeek Flash 输出验证

**API 调用**：耗时 14.2s，input 915 tokens / output 1517 tokens / 合计 2432 tokens

| 验证项 | 预期 | 实际 | 判定 |
|--------|------|------|:---:|
| 代码 | 300373 | 300373 | ✅ |
| 交易所 | SZSE | SZSE | ✅ |
| 行业分类 | 电子/半导体/分立器件 | 电子/半导体/分立器件 | ✅ |
| 上市日期 | 2014-01-23 | 2014-01-23 | ✅ |
| 商业模式 | FabLite | FABLITE | ✅ |
| 法人/实控人 | 梁勤 | 梁勤 | ✅ |
| 概念标签 | 功率半导体/IGBT/SiC 等 | 功率半导体,IGBT,第三代半导体,汽车电子,充电桩,光伏,国产替代 | ✅ |
| 产品线 | ≥3 条 | 4 条（二极管→MOSFET→IGBT→SiC）| ✅ |
| 竞品 | ≥2 家 | 华润微(688396) + 士兰微(600460) | ✅ |
| 风险 | ≥2 项 | 2 项（结构性+周期性）| ✅ |

**结论**：全部验证通过，DeepSeek Flash 对扬杰科技的知识覆盖准确完整。

### 4.3 富满微 (300671) DeepSeek Flash 输出验证

**API 调用**：耗时 15.8s，input 915 tokens / output 1810 tokens / 合计 2725 tokens

| 验证项 | 预期 | 实际 | 判定 |
|--------|------|------|:---:|
| 代码 | 300671 | 300671 | ✅ |
| 交易所 | SZSE | SZSE | ✅ |
| 行业分类 | 电子/半导体/模拟芯片设计 | 电子/半导体/模拟芯片设计 | ✅ |
| 商业模式 | Fabless | FABLESS | ✅ |
| 法人/实控人 | 刘景裕 | 刘景裕 | ✅ |
| 产品线 | ≥3 条 | 5 条（电源管理→LED驱动→MOSFET→射频→MCU）| ✅ |
| 竞品 | ≥2 家 | 圣邦股份(300661) + 明微电子(688699) | ✅ |
| 风险 | ≥2 项 | 2 项 | ✅ |

**结论**：全部验证通过。竞品选择非常精准（圣邦股份是国内模拟芯片龙头，明微电子是 LED 驱动直接竞争对手），护城河/稀缺性评价合理。

### 4.4 Kimi vs DeepSeek 对比分析

两个标的均调用 DeepSeek Flash 成功，对比 KimiDataAgent DEEP 模式的区别：

| 维度 | Kimi (同花顺+天眼查) | DeepSeek Flash (公开知识) | 差距评估 |
|------|----------------------|---------------------------|----------|
| 公司基本信息 | 精确 | 精确 | **无差距** |
| 商业模式识别 | FabLite/Fabless 精确 | FabLite/Fabless 精确 | **无差距** |
| 产业链定位 | 同花顺产业数据支撑 | 训练数据支撑 | **无差距**（定性分析） |
| 产品营收占比 | 精确百分比（如同花顺主营构成） | 近似值（如"约 50%"） | 可接受 |
| 竞品对比（定性） | 基于同花顺同行对比 | 训练数据支撑 | **无差距** |
| 竞品精确财务 | 精确市值/营收/净利 | 不提供 | Kimi 优势 |
| 资金流向 | ✅ 精确数据 | ❌ 无法提供 | Kimi 专属 |
| 筹码分布 | ✅ 精确数据 | ❌ 无法提供 | Kimi 专属 |
| 财务三表 | ✅ 精确数字 | ❌ 无法提供 | Kimi 专属 |

> **核心发现**：在定性分析维度（公司概况、产业链、竞品定性评价），DeepSeek Flash 与 Kimi 表现相当，且完全免费。分组边界验证成立。

---

## 5. 调用顺序建议（流水线模式）

```
用户输入: "分析 扬杰科技 300373"
    │
    ├─ Phase 1: DeepSeek Flash（免费，先跑）
    │   ├─ stock（基础字段）
    │   ├─ stock_company（描述字段）
    │   ├─ stock_industry_chain（全量）
    │   ├─ stock_product_breakdown（描述字段 + 近似数字）
    │   └─ stock_competitor（定性评价）
    │   → 输出: output/ds_扬杰科技_analysis.json
    │
    ├─ Phase 2: Kimi（付费，后跑，只拿 DS 拿不到的）
    │   ├─ stock_quote（行情快照）
    │   ├─ stock_daily（日K线）
    │   ├─ stock_money_flow（资金流向）
    │   ├─ stock_chip_structure（筹码分布）
    │   ├─ 财务四表: income/balance/cashflow/financial
    │   ├─ 股东两表: holder/top_holder
    │   ├─ stock_valuation（估值）
    │   ├─ market_index + market_aggregate（市场数据）
    │   └─ 混合表的精确字段补充
    │   → 输出: output/扬杰科技_analysis.json
    │
    └─ Phase 3: 本地计算
        └─ stock_daily_indicator (MA/MACD/RSI/KDJ/BOLL)
```

**关键优化**：
1. DeepSeek 先行（免费/廉价），快速获取定性全貌
2. Kimi 后行（付费），只拿 DS 无法覆盖的精确数据，节省 Token
3. 混合表（stock/company/product/competitor）由两个 Agent 各取所需，后端合并时 DS 覆盖定性字段、Kimi 覆盖精确字段

---

## 6. 文件清单

| 文件 | 位置 | 说明 |
|------|------|------|
| DeepSeekDataAgent.java | `agent-scope/src/main/java/demo/` | DeepSeek Flash 数据采集智能体 |
| KimiDataAgent.java | `agent-scope/src/main/java/demo/` | Kimi 数据采集智能体（已有，不改） |
| 验证输出（扬杰科技） | `agent-scope/output/ds_扬杰科技_analysis.json` | DS Flash 对 300373 的输出 |
| 验证输出（富满微） | `agent-scope/output/ds_富满微_analysis.json` | DS Flash 对 300671 的输出 |
| 本分组文档 | `docs/design/data-source-split.md` | 数据源分组方案 |

---

## 7. 运行命令

```bash
# DeepSeek Flash（免费层）
cd agent-scope
mvn exec:java -Dexec.mainClass="demo.DeepSeekDataAgent" -Dexec.args="扬杰科技 300373"

# Kimi（付费层，需要时再跑）
cd agent-scope
mvn exec:java -Dexec.mainClass="demo.KimiDataAgent" -Dexec.args="扬杰科技 300373 --mode deep"
```
