---
name: analyze-stock
description: 触发 StockTradingSystem 后端（8080）的股票分析工作流（11 节点全面分析）并跟踪到报告产出。当用户输入 /analyze-stock 加股票代码或股票名，或说"分析XX股票"、"跑一下XX的全面分析"、"帮我分析下XX"时使用本技能。
---

# 股票分析工作流触发（StockTradingSystem）

确定性执行协议：收到股票代码/名称后，严格按下列步骤执行，无需再做意图推断。

## 步骤 1：确认后端在线

```powershell
netstat -ano | findstr ":8080 " | findstr LISTENING
```

无输出则启动后端（后台运行，is_background=true，禁止清库）：

```powershell
mvn -f d:\code\StockTradingSystem\backend\pom.xml spring-boot:run
```

等待日志出现 `Started StockApplication`（约 10 秒）再继续。

## 步骤 2：解析股票代码

输入是 6 位数字代码则跳过本步；输入是股票名则调解析接口。**含中文的 JSON body 必须先用 Write 工具落盘成文件再 `--data-binary "@file"` 传入**（本 shell 中 `-d "{}"` 的转义会被透传导致 JSON 解析失败）：

```powershell
curl.exe -s -X POST "http://localhost:8080/api/v1/analysis-agent/resolve" -H "Content-Type: application/json" --data-binary "@<body.json>"
# body.json 内容: {"query":"<股票名>"}
```

返回候选数组 `[{code, name, market, industry}]`，取第一条的 code/name。多条同名时向用户确认。

## 步骤 3：触发工作流

工作流 ID=2（股票分析工作流，STOCK_ANALYSIS，已发布）。goal 用户未指定时用默认值。body 同样落盘后 `--data-binary "@file"` 传入：

```powershell
curl.exe -s --max-time 180 -X POST "http://localhost:8080/api/v1/workflow/2/execute" -H "Content-Type: application/json" --data-binary "@<body.json>"
# body.json 内容: {"input":{"stockCode":"<code>","stockName":"<name>","goal":"<goal 或: 全面分析>"}}
```

后端在执行前走**数据准备闸门**：盘点本地库，缺行情/K线/财务/资金流自动拉取（行情首选 Serper、未命中降级东财；K线走腾讯直连→新浪→Kimi 兜底；财务/资金流走东财；可能阻塞约 30 秒，故加 `--max-time 180`），随后预注入五个数据上下文，无需手动跑 `import_stock.py`。若想单独验证/补齐某标的的本地数据而不启动工作流，可走运维端点 `POST /api/stock/<code>/acquire`（返回 tradeable/K线/财务/资金流计数与每步明细，不烧 token）。

**响应两种情形**：
- 成功：`{"processInstanceId":"<pid>"}` → 进步骤 4
- 409 快速失败：`{"error":...,"message":"股票 <code> 数据不足且自动拉取失败..."}` → **不进入轮询**，直接把 message 中的原因转述给用户（通常是代码无效或腾讯/新浪/Kimi/东财均拉不到 K线），询问是否换代码/稍后重试

## 步骤 4：轮询到终态

用脚本一步完成（45 分钟超时，30 秒间隔）：

```powershell
powershell -ExecutionPolicy Bypass -File .qoder/skills/analyze-stock/poll.ps1 -InstanceId "<pid>"
```

脚本在 COMPLETED/FAILED/CANCELLED/TIMEOUT 时退出并打印最终状态与各节点状态（脚本为纯 ASCII，规避 PowerShell 5.1 按 ANSI 读 UTF-8 无 BOM 文件导致中文乱码解析失败的问题，修改时保持 ASCII）。
轮询期间每 2-3 分钟向用户同步一次进度（已完成节点数/11）。

## 步骤 5：取报告并呈现

COMPLETED 后：

```powershell
curl.exe -s "http://localhost:8080/api/reports?stockCode=<code>"
```

取返回中本次 runId（processInstanceId 即 runId 关联键，取最新一条），再取明细：

```powershell
curl.exe -s "http://localhost:8080/api/reports/<runId>"
```

向用户呈现：综合研判结论 + 风险提示 + 报告导出方式（`/api/reports/<runId>/export?format=md`）。
FAILED 时打印失败节点名与原因，询问是否重试。

## 协议约定

- 数据为 MySQL 本地库 T+1 盘后口径；本地缺数由后端闸门自动补数（行情 Serper/东财，K线腾讯/新浪/Kimi，财务/资金流东财；无需手动跑 data-fetcher 脚本），工作流节点输出基于库内真实数据
- PowerShell 不支持 `&&`，多命令用 `;` 分隔；curl 一律用 `curl.exe`（避免 Invoke-WebRequest 别名）；**含中文的 POST body 一律落盘文件 + `--data-binary "@file"`，禁止 `-d "{}"` 内联转义**（本 shell 会把反斜杠透传给服务端导致 400）
- 触发后工作流约 10~30 分钟（11 个 LLM 节点），绝不中途取消除非用户要求
- 工作流 ID 若变更（非 2），先 `GET /api/v1/workflow/list` 找 category=STOCK_ANALYSIS 且 status=PUBLISHED 的那条
