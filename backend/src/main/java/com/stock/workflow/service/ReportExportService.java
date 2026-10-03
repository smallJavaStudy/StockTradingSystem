package com.stock.workflow.service;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/**
 * 报告导出组装：Markdown 直接拼装（标题 = 工作流名 + 股票 + 日期，按节点顺序编排输出），
 * HTML 由 commonmark 将 Markdown 渲染后套简洁模板。
 */
@Service
public class ReportExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().escapeHtml(true).build();

    /** 组装完整 Markdown 报告 */
    public String toMarkdown(ReportRunDetail detail) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(buildTitle(detail)).append("\n\n");

        md.append("- **运行ID**: ").append(detail.runId()).append('\n');
        if (detail.stockCode() != null) {
            md.append("- **股票**: ").append(stockLabel(detail)).append('\n');
        }
        md.append("- **状态**: ").append(nullSafe(detail.status())).append('\n');
        md.append("- **开始时间**: ").append(formatTime(detail.startTime())).append('\n');
        md.append("- **结束时间**: ").append(formatTime(detail.endTime())).append('\n');
        md.append("- **节点数**: ").append(detail.nodes().size()).append("\n\n");

        int index = 0;
        for (ReportNodeDetail node : detail.nodes()) {
            index++;
            md.append("---\n\n");
            md.append("## ").append(index).append(". ")
                    .append(node.nodeName() != null ? node.nodeName() : node.nodeId()).append("\n\n");
            md.append("> 状态: ").append(nullSafe(node.status()));
            if (node.durationMs() != null) {
                md.append(" · 耗时: ").append(formatDuration(node.durationMs()));
            }
            md.append("\n\n");
            if (node.errorMessage() != null && !node.errorMessage().isBlank()) {
                md.append("**错误信息**: ").append(node.errorMessage()).append("\n\n");
            }
            if (node.output() != null && !node.output().isBlank()) {
                md.append(node.output().trim()).append("\n\n");
            } else {
                md.append("_（无输出）_\n\n");
            }
        }
        return md.toString();
    }

    /** Markdown 渲染为 HTML 并套简洁模板 */
    public String toHtml(ReportRunDetail detail) {
        Node document = markdownParser.parse(toMarkdown(detail));
        String body = htmlRenderer.render(document);
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <title>%s</title>
                <style>
                body { max-width: 860px; margin: 24px auto; padding: 0 16px; color: #333;
                       font-family: "Segoe UI", "Microsoft YaHei", sans-serif; line-height: 1.7; }
                h1 { border-bottom: 2px solid #1890ff; padding-bottom: 8px; }
                h2 { margin-top: 32px; border-bottom: 1px solid #eee; padding-bottom: 4px; }
                blockquote { margin: 0; padding: 4px 12px; background: #f6f8fa; border-left: 4px solid #1890ff; color: #555; }
                pre { background: #f6f8fa; padding: 12px; border-radius: 6px; overflow-x: auto; }
                code { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; font-size: 90%%; }
                pre code { background: none; padding: 0; }
                table { border-collapse: collapse; width: 100%%; }
                th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; }
                th { background: #fafafa; }
                hr { border: none; border-top: 1px solid #eee; margin: 24px 0; }
                </style>
                </head>
                <body>
                %s</body>
                </html>
                """.formatted(escapeHtml(buildTitle(detail)), body);
    }

    /** 导出文件名（不含扩展名）：report-工作流名-股票代码-日期 */
    public String buildFileName(ReportRunDetail detail) {
        StringBuilder name = new StringBuilder("report");
        if (detail.workflowName() != null && !detail.workflowName().isBlank()) {
            name.append('-').append(detail.workflowName().trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_"));
        }
        if (detail.stockCode() != null) {
            name.append('-').append(detail.stockCode());
        }
        if (detail.startTime() != null) {
            name.append('-').append(DATE_FMT.format(detail.startTime()));
        }
        return name.toString();
    }

    /** 标题 = 工作流名 + 股票 + 日期 */
    private String buildTitle(ReportRunDetail detail) {
        StringBuilder title = new StringBuilder();
        title.append(detail.workflowName() != null ? detail.workflowName() : "工作流运行报告");
        String stock = stockLabel(detail);
        if (stock != null) {
            title.append(" - ").append(stock);
        }
        if (detail.startTime() != null) {
            title.append(" (").append(DATE_FMT.format(detail.startTime())).append(')');
        }
        return title.toString();
    }

    /** 股票展示标签：股票名(代码) / 仅代码 / null */
    private String stockLabel(ReportRunDetail detail) {
        if (detail.stockCode() == null) return null;
        return detail.stockName() != null
                ? detail.stockName() + "(" + detail.stockCode() + ")"
                : detail.stockCode();
    }

    private String formatTime(java.time.LocalDateTime time) {
        return time != null ? TIME_FMT.format(time) : "-";
    }

    private String formatDuration(long ms) {
        long sec = ms / 1000;
        return sec >= 60 ? (sec / 60) + "分" + (sec % 60) + "秒" : sec + "秒";
    }

    private String nullSafe(String s) {
        return s != null ? s : "-";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
