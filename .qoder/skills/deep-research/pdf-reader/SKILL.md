---
name: pdf-reader
description: PDF深度阅读——将中文PDF（特别是嵌入式CID字体无法文本提取的）转为结构化分析摘要。当需要读取研究报告PDF但文本提取失败时使用。依赖Tesseract OCR + 中文语言包。
group: deep-research
category: 研究设计
---

# PDF深度阅读

## 何时使用

- PDF的文本提取失败（CID字体、扫描件、图片型PDF）
- 需要从PDF中提取结构化信息而非逐字还原
- 研究报告PDF（封面+目录+正文+图表），需要快速把握核心框架和数据

## 工作原理

```
PDF → PyMuPDF渲染(200DPI PNG) → Tesseract OCR(chi_sim) → 
→ LLM结构化提取(框架/数据/结论) → 结构化JSON摘要
```

**核心设计原则：不逐字还原，只提取结构。** 这节省了80%的token——研究报告中的装饰性文字、过渡段落、重复表述全部跳过，只保留分析框架、关键数据点、核心结论。

## 环境依赖

```bash
# 1. 安装 Tesseract OCR
winget install UB-Mannheim.TesseractOCR
# 或
winget install tesseract-ocr.tesseract

# 2. 下载中文语言包
# https://github.com/tesseract-ocr/tessdata/blob/main/chi_sim.traineddata
# 放到 Tesseract 安装目录的 tessdata 子目录下

# 3. Python依赖
pip install PyMuPDF pytesseract Pillow
```

## 使用方式

### 单文件阅读
```
输入：PDF文件路径
输出：结构化JSON（框架、数据点、结论、图表清单）
```

### 批量阅读（多文件课题）
```
输入：包含多个PDF的目录
输出：跨文件的结构化对比摘要
```

## 输出结构

```json
{
  "report_title": "报告标题",
  "pages": 34,
  "sections": [
    {"heading": "章节标题", "page": 1, "summary": "1-2句话摘要"}
  ],
  "key_data_points": [
    {"value": "具体数值", "unit": "单位", "context": "上下文"}
  ],
  "core_framework": "报告使用的分析框架（1段话）",
  "main_conclusion": "核心结论",
  "counter_consensus": "与市场共识的不同之处",
  "charts_tables": [
    {"type": "图表类型", "page": 5, "description": "图表内容简述"}
  ],
  "investable_insights": [
    "可转化为投资操作的洞察"
  ]
}
```

## Token节省策略

| 阶段 | 原始token | 优化后token | 节省率 |
|------|----------|------------|-------|
| PDF全文本 | 15000-50000 | - | - |
| OCR原始文本 | 8000-30000 | - | - |
| 结构化提取 | - | 2000-5000 | **70-85%** |

节省来自：
1. 跳过封面、目录、免责声明
2. 跳过修辞性和过渡性文字
3. 表格数据只保留关键行列
4. 图表只记录类型和核心信息，不逐字描述
5. 章节用摘要替代全文

## 多文件课题的交叉阅读

当一个课题包含3份PDF（主报告+3份系列研究），使用交叉阅读模式：

```
交叉阅读输出：
  - 3份报告的共同主题和各自独特角度
  - 跨报告的数据/框架一致性检验
  - 3份报告之间的逻辑递进关系
  - 综合投资结论（非简单叠加）
```

## 注意事项

1. **OCR精度**：中文OCR准确率约95-98%，关键数字需要交叉验证
2. **图表限制**：Tesseract无法识别图表内容，图表信息记录为"图表存在"标记，需人工查看
3. **公式/代码**：PDF中的公式和代码块OCR效果差，会被标记为跳过
4. **大PDF分批**：超过50页的PDF分批处理，每批不超过15页
