package com.stock.workflow.controller;

import com.stock.workflow.service.ReportExportService;
import com.stock.workflow.service.ReportLibraryService;
import com.stock.workflow.service.ReportRunDetail;
import com.stock.workflow.service.ReportRunSummary;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报告库：工作流运行报告的检索 / 明细 / 导出。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportLibraryController {

    private final ReportLibraryService reportService;
    private final ReportExportService exportService;

    public ReportLibraryController(ReportLibraryService reportService,
                                   ReportExportService exportService) {
        this.reportService = reportService;
        this.exportService = exportService;
    }

    /** 分页检索运行级汇总（时间倒序），全部筛选参数可空 */
    @GetMapping
    public Map<String, Object> search(@RequestParam(required = false) String stockCode,
                                      @RequestParam(required = false) String workflowName,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        Page<ReportRunSummary> result =
                reportService.search(stockCode, workflowName, startDate, endDate, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.getContent());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return body;
    }

    /** 单次运行的全部节点明细（完整输出） */
    @GetMapping("/{runId}")
    public ReportRunDetail detail(@PathVariable String runId) {
        return reportService.getRunDetail(runId);
    }

    /** 导出完整报告文件，format=md|html */
    @GetMapping("/{runId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String runId,
                                         @RequestParam(defaultValue = "md") String format) {
        boolean html = switch (format.toLowerCase()) {
            case "md" -> false;
            case "html" -> true;
            default -> throw new IllegalArgumentException("不支持的导出格式: " + format + "（仅支持 md/html）");
        };
        ReportRunDetail detail = reportService.getRunDetail(runId);
        String content = html ? exportService.toHtml(detail) : exportService.toMarkdown(detail);
        String fileName = exportService.buildFileName(detail) + (html ? ".html" : ".md");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(html
                ? MediaType.TEXT_HTML
                : MediaType.parseMediaType("text/markdown;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(content.getBytes(StandardCharsets.UTF_8), headers,
                org.springframework.http.HttpStatus.OK);
    }
}
