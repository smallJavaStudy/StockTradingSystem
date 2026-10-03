package com.stock.workflow.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowTextUtils} 单元测试：非 BMP 净化（emoji / 生僻字 / 正常中文）与截断阈值。
 */
class WorkflowTextUtilsTest {

    @Test
    @DisplayName("stripNonBmp：剔除 emoji（4 字节 UTF-8 补充平面字符）")
    void stripsEmoji() {
        String input = "📌 核心结论：建议买入 🚀，风险提示 ⚠️ 保留";
        String result = WorkflowTextUtils.stripNonBmp(input);
        // 📌(U+1F4CC)、🚀(U+1F680) 为补充平面字符被剔除；⚠(U+26A0)+变体选择符(U+FE0F) 属 BMP 保留
        assertThat(result).isEqualTo(" 核心结论：建议买入 ，风险提示 ⚠️ 保留");
        assertThat(result.codePoints().anyMatch(cp -> cp > 0xFFFF)).isFalse();
    }

    @Test
    @DisplayName("stripNonBmp：剔除 CJK 扩展 B 生僻字，保留 BMP 内生僻字")
    void stripsSupplementaryRareChars() {
        // 𠀀(U+20000, CJK 扩展 B) 超出 BMP 应剔除；龘(U+9F98) 属 BMP 应保留
        String input = "生僻字𠀀测试龘结束";
        assertThat(WorkflowTextUtils.stripNonBmp(input)).isEqualTo("生僻字测试龘结束");
    }

    @Test
    @DisplayName("stripNonBmp：正常中英文数字标点原样保留")
    void keepsNormalText() {
        String input = "300364 中文在线：全面分析报告。ROE=12.5%，K线呈多头排列！";
        assertThat(WorkflowTextUtils.stripNonBmp(input)).isEqualTo(input);
        assertThat(WorkflowTextUtils.stripNonBmp(null)).isNull();
        assertThat(WorkflowTextUtils.stripNonBmp("")).isEmpty();
    }

    @Test
    @DisplayName("truncate：阈值内不截断，超阈值截断并追加后缀")
    void truncatesOverThreshold() {
        assertThat(WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS).isGreaterThanOrEqualTo(60000);

        String within = "a".repeat(WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS);
        assertThat(WorkflowTextUtils.truncate(within, WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS))
                .isSameAs(within);

        String over = "b".repeat(WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS + 1);
        String truncated = WorkflowTextUtils.truncate(over, WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS);
        assertThat(truncated)
                .hasSize(WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS + "\n...(已截断)".length())
                .endsWith("\n...(已截断)");

        assertThat(WorkflowTextUtils.truncate(null, 10)).isNull();
    }
}
