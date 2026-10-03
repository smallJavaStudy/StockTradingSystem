package com.stock.workflow.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportExportService} 单元测试：Markdown 报告组装与 HTML 模板渲染。
 */
class ReportExportServiceTest {

    private final ReportExportService service = new ReportExportService();

    private ReportRunDetail sampleDetail() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 20, 10, 0, 0);
        return new ReportRunDetail(
                "pid-1", "股票分析工作流", "600519", "贵州茅台", "COMPLETED",
                t0, t0.plusMinutes(5),
                List.of(
                        new ReportNodeDetail("n1", "技术分析", "COMPLETED",
                                "## 趋势\n**上升通道**成立", null, t0, t0.plusMinutes(1), 60_000L),
                        new ReportNodeDetail("n2", "综合建议", "COMPLETED",
                                "建议**买入**", null, t0.plusMinutes(1), t0.plusMinutes(5), 240_000L)));
    }

    @Test
    @DisplayName("Markdown：标题含工作流名+股票+日期，节点按顺序编排")
    void markdown_titleAndNodeOrder() {
        String md = service.toMarkdown(sampleDetail());

        assertThat(md).startsWith("# 股票分析工作流 - 贵州茅台(600519) (2026-07-20)");
        assertThat(md).contains("- **运行ID**: pid-1");
        assertThat(md).contains("- **状态**: COMPLETED");
        assertThat(md).contains("## 1. 技术分析");
        assertThat(md).contains("## 2. 综合建议");
        assertThat(md.indexOf("## 1. 技术分析")).isLessThan(md.indexOf("## 2. 综合建议"));
        assertThat(md).contains("> 状态: COMPLETED · 耗时: 1分0秒");
        assertThat(md).contains("**上升通道**成立");
    }

    @Test
    @DisplayName("Markdown：无输出节点与失败节点的占位/错误信息")
    void markdown_emptyOutputAndError() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 20, 10, 0);
        ReportRunDetail detail = new ReportRunDetail(
                "pid-2", null, null, null, "FAILED", t0, null,
                List.of(new ReportNodeDetail("n1", "技术分析", "FAILED",
                        null, "LLM 超时", t0, t0.plusSeconds(30), 30_000L)));

        String md = service.toMarkdown(detail);

        assertThat(md).startsWith("# 工作流运行报告 (2026-07-20)"); // 工作流名缺失时回退标题
        assertThat(md).doesNotContain("- **股票**"); // 无股票信息不输出该行
        assertThat(md).contains("**错误信息**: LLM 超时");
        assertThat(md).contains("_（无输出）_");
        assertThat(md).contains("- **结束时间**: -");
    }

    @Test
    @DisplayName("HTML：Markdown 渲染为 HTML 且套模板")
    void html_rendersMarkdown() {
        String html = service.toHtml(sampleDetail());

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<title>股票分析工作流 - 贵州茅台(600519) (2026-07-20)</title>");
        assertThat(html).contains("<h1>股票分析工作流 - 贵州茅台(600519) (2026-07-20)</h1>");
        assertThat(html).contains("<h2>1. 技术分析</h2>");
        assertThat(html).contains("<strong>上升通道</strong>"); // 节点输出内的 Markdown 也被渲染
        assertThat(html).contains("</html>");
    }

    @Test
    @DisplayName("HTML：节点输出中的原始 HTML 被转义，防注入")
    void html_escapesRawHtml() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 20, 10, 0);
        ReportRunDetail detail = new ReportRunDetail(
                "pid-3", "分析", "000001", null, "COMPLETED", t0, t0.plusMinutes(1),
                List.of(new ReportNodeDetail("n1", "节点", "COMPLETED",
                        "<script>alert(1)</script>", null, t0, t0.plusMinutes(1), 60_000L)));

        String html = service.toHtml(detail);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("导出文件名：report-工作流名-股票代码-日期，非法字符替换")
    void buildFileName() {
        assertThat(service.buildFileName(sampleDetail()))
                .isEqualTo("report-股票分析工作流-600519-2026-07-20");

        LocalDateTime t0 = LocalDateTime.of(2026, 7, 20, 10, 0);
        ReportRunDetail weird = new ReportRunDetail(
                "pid-4", "a/b:c 分析", null, null, "COMPLETED", t0, null, List.of());
        assertThat(service.buildFileName(weird)).isEqualTo("report-a_b_c_分析-2026-07-20");
    }
}
