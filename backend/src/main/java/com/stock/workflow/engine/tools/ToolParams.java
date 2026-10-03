package com.stock.workflow.engine.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 工具入参解析辅助：LLM 传参一律按字符串接收，宽松解析。 */
final class ToolParams {

    private ToolParams() {}

    /** 解析天数参数：空/非数字用默认值，并限制在 [min, max] 区间 */
    static int parseDays(String raw, int defaultDays, int min, int max) {
        int days = defaultDays;
        if (raw != null && !raw.isBlank()) {
            try {
                days = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // 非数字入参：保持默认值
            }
        }
        return Math.max(min, Math.min(max, days));
    }

    /** 校验股票代码：6 位数字返回规范化值，否则返回 null */
    static String normalizeStockCode(String raw) {
        if (raw == null) return null;
        String code = raw.trim();
        return code.matches("\\d{6}") ? code : null;
    }

    /** 解析日期参数：兼容 yyyy-MM-dd 与 yyyyMMdd，空/非法返回 null（由调用方决定回退策略） */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.matches("\\d{8}")) {
            v = v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6);
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
