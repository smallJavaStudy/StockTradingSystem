---
name: dd-checklist
description: 对详细设计文档进行系统性自检，发现类职责缺陷、方法签名遗漏、字段映射错误、SQL不合理和与概要设计/需求的不一致。在详细设计完成后、进入编码前使用此技能。
---

# 详细设计自检清单

## 使用方式

对项目的详细设计文档（detailed-design.md）逐项过以下检查清单，输出一份自检报告。
每条检查给出：✅ 通过 / ⚠️ 有风险 / ❌ 不通过，附具体问题说明。
交叉参考概要设计（high-level-design.md）、需求文档（SKILL.md、api-design.md、data-fetcher.md）。

---

## 一、类职责单一性

| 编号 | 检查项 | 通过标准 |
|------|--------|---------|
| C1 | 每个Service类是否只负责一个业务领域？ | 一个Service不包含两个不相关功能域的方法（如StockService不包含K线计算逻辑） |
| C2 | Controller是否只做路由和参数校验？ | 无业务逻辑在Controller中，复杂逻辑委托给Service |
| C3 | Entity是否只做数据映射？ | 无业务方法在Entity中，只有getter/setter |
| C4 | DTO是否与Entity分离？ | 请求DTO和响应DTO独立于Entity，不暴露内部字段结构 |
| C5 | 工具类是否无状态？ | HtmlSanitizer等工具类无实例变量，纯函数 |

## 二、方法签名完整性

| 编号 | 检查项 | 通过标准 |
|------|--------|---------|
| M1 | 每个Controller方法是否有对应的Service方法？ | Controller每个端点的业务逻辑在Service中有对应方法 |
| M2 | 每个Service方法的入参和返回值类型是否明确？ | 无Object/Map等模糊类型作为核心参数或返回值 |
| M3 | 每个方法是否标注了异常情况？ | 业务异常（NotFound/Duplicate/Invalid）有明确抛出点 |
| M4 | 手动数据的校验注解是否完整？ | @NotNull/@NotBlank/@Size/@DecimalMin/@DecimalMax等覆盖SKILL.md验证规则表 |
| M5 | Repository查询方法是否能覆盖所有业务场景？ | 每个Service的查询需求在Repository中有对应方法签名 |

## 三、字段映射正确性

| 编号 | 检查项 | 通过标准 |
|------|--------|---------|
| F1 | 外部接口字段→Python字段→Java DTO字段→DB字段是否贯通？ | 每类数据的字段映射链完整，无断点或名称不匹配 |
| F2 | 精度处理是否在正确层执行？ | push2的÷100在Python层完成，datacenter利润表不÷100已确认，Java层不做精度转换 |
| F3 | 空值处理是否有说明？ | 外部接口可能返回null的字段在映射中有说明（如holder字段待确认） |
| F4 | 枚举值在所有层是否一致？ | lifecycle_stage/moat_level/alert type/note category等在Entity/DTO/DB/前端完全匹配 |
| F5 | 数据类型是否匹配？ | 金额用BIGINT/Long不用浮点，百分比用DECIMAL，日期用DATE/Timestamp |

## 四、SQL合理性

| 编号 | 检查项 | 通过标准 |
|------|--------|---------|
| S1 | 每张表是否有主键？ | AUTO_INCREMENT BIGINT |
| S2 | 外键关系是否正确？ | 子表有FK指向stock(id)，级联关系明确 |
| S3 | 唯一约束是否覆盖去重场景？ | stock(code), stock_daily(stock_id,trade_date), stock_income(stock_id,report_date,report_type)等 |
| S4 | CHECK约束是否覆盖业务规则？ | stock_competitor(stock_id!=competitor_stock_id), price_alert(threshold>0)等 |
| S5 | 索引是否覆盖高频查询？ | 唯一约束自动建索引，外键字段是否需额外索引视查询频率而定 |
| S6 | 字段长度是否合理？ | VARCHAR长度与业务匹配（code=6, name=20, title=500等） |

## 五、与概要设计的追溯完整性

| 编号 | 检查项 | 通过标准 |
|------|--------|---------|
| T1 | 追溯矩阵是否覆盖所有详细设计编号？ | 每个DD-xx都有对应的HLD编号和需求功能 |
| T2 | 概要设计的每个模块在详细设计中是否有实现？ | HLD提到的18个Controller/Service在DD中有方法签名 |
| T3 | 概要设计的每条数据流在详细设计中是否有落地？ | HLD的8类自动数据流和6类手动数据流在DD中有字段映射和方法调用链 |
| T4 | 概要设计的设计决策在详细设计中是否体现？ | HLD D1~D7决策在DD中有对应实现（如D4的PUT语义、D5的交叉调用顺序、D7的纯文本存储） |

## 六、与API设计的一致性

| 编号 | 检查项 | 通过标准 |
|------|--------|---------|
| A1 | Controller路径是否与api-design.md一致？ | 每个Controller的@RequestMapping路径与api-design.md完全匹配 |
| A2 | 请求DTO字段是否与api-design.md请求体一致？ | 字段名、类型、必填/选填对齐 |
| A3 | 响应DTO字段是否与api-design.md响应体一致？ | 字段名、类型对齐 |
| A4 | HTTP方法是否与api-design.md一致？ | GET/POST/PUT/DELETE对应关系正确 |
| A5 | 错误码是否与api-design.md一致？ | 400/404/409/503等错误场景在异常体系中有对应处理 |

---

## 自检报告模板

```markdown
# 详细设计自检报告

**项目名称**：
**自检日期**：
**自检文档**：detailed-design.md（交叉参考 high-level-design.md、api-design.md、data-fetcher.md、SKILL.md）

## 检查结果汇总

| 类别 | 通过 | 有风险 | 不通过 |
|------|------|--------|--------|
| 类职责单一性 | /5 | | |
| 方法签名完整性 | /5 | | |
| 字段映射正确性 | /5 | | |
| SQL合理性 | /6 | | |
| 追溯完整性 | /4 | | |
| API一致性 | /5 | | |

## 逐项详情

### 一、类职责单一性
- C1: [结果] [说明]
- ...

（以此类推）

## 必须修复项（不通过项）
1. ...

## 建议改进项（有风险项）
1. ...
```
