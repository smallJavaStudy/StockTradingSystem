package com.stock.workflow.engine;

import java.util.regex.Pattern;

/**
 * 工作流文本处理工具。
 * <p>
 * 背景：MySQL utf8（三字节）无法存储 BMP 之外的字符（如 emoji，4 字节 UTF-8）。
 * LLM 输出含 emoji 时，Flowable 写 ACT_HI_VARINST 会失败并使异步作业进入死信队列，
 * 导致后续节点永不启动。因此所有入库文本（流程变量 / 运行日志）必须先剔除非 BMP 字符。
 * SSE 推送不入库，无需净化。
 */
public final class WorkflowTextUtils {

    /** 节点输出对外展示 / SSE 推送的截断阈值（字符数） */
    public static final int OUTPUT_TRUNCATE_CHARS = 60000;

    /** BMP 之外的补充平面字符（codePoint > 0xFFFF，含 emoji） */
    private static final Pattern NON_BMP = Pattern.compile("[\\x{10000}-\\x{10FFFF}]");

    private WorkflowTextUtils() {}

    /** 剔除所有非 BMP 字符（emoji 等），保证 MySQL utf8 三字节字符集可安全存储 */
    public static String stripNonBmp(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return NON_BMP.matcher(text).replaceAll("");
    }

    /** 超过 maxChars 截断并追加提示后缀 */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        return text.length() > maxChars
                ? text.substring(0, maxChars) + "\n...(已截断)"
                : text;
    }
}
