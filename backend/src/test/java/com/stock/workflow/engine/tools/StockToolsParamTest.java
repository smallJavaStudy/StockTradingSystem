package com.stock.workflow.engine.tools;

import com.stock.workflow.engine.StockContextPreloader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 轨道 B 自定义工具的参数解析单元测试：覆盖 {@link ToolParams} 宽松解析、
 * 工具入口的非法参数兜底与 {@link DataEnrichTool#buildDirectionKey} 截断逻辑。
 */
class StockToolsParamTest {

    // ─────────────────── ToolParams.parseDays ───────────────────

    @Nested
    @DisplayName("parseDays 宽松解析")
    class ParseDays {

        @Test
        @DisplayName("null/空白/非数字：使用默认值")
        void fallbackToDefault() {
            assertThat(ToolParams.parseDays(null, 120, 5, 250)).isEqualTo(120);
            assertThat(ToolParams.parseDays("  ", 120, 5, 250)).isEqualTo(120);
            assertThat(ToolParams.parseDays("abc", 20, 5, 60)).isEqualTo(20);
        }

        @Test
        @DisplayName("合法数字：去空格解析")
        void parsesNumber() {
            assertThat(ToolParams.parseDays(" 30 ", 120, 5, 250)).isEqualTo(30);
        }

        @Test
        @DisplayName("越界：clamp 到 [min, max]")
        void clamps() {
            assertThat(ToolParams.parseDays("1", 120, 5, 250)).isEqualTo(5);
            assertThat(ToolParams.parseDays("9999", 120, 5, 250)).isEqualTo(250);
            assertThat(ToolParams.parseDays("-3", 20, 5, 60)).isEqualTo(5);
        }
    }

    // ─────────────────── ToolParams.normalizeStockCode ───────────────────

    @Nested
    @DisplayName("normalizeStockCode 校验")
    class NormalizeStockCode {

        @Test
        @DisplayName("6 位数字（含前后空格）：规范化返回")
        void validCode() {
            assertThat(ToolParams.normalizeStockCode("600519")).isEqualTo("600519");
            assertThat(ToolParams.normalizeStockCode(" 000858 ")).isEqualTo("000858");
        }

        @Test
        @DisplayName("null/长度不符/含字母：返回 null")
        void invalidCode() {
            assertThat(ToolParams.normalizeStockCode(null)).isNull();
            assertThat(ToolParams.normalizeStockCode("60051")).isNull();
            assertThat(ToolParams.normalizeStockCode("6005199")).isNull();
            assertThat(ToolParams.normalizeStockCode("SH600519")).isNull();
        }
    }

    // ─────────────────── 工具入口兜底 ───────────────────

    @Nested
    @DisplayName("工具入口参数兜底")
    class ToolEntry {

        @Test
        @DisplayName("stock_kline：非法 stockCode 返回错误文本，不查库")
        void klineRejectsBadCode() {
            StockContextPreloader preloader = mock(StockContextPreloader.class);
            StockKlineTool tool = new StockKlineTool(preloader);

            String result = tool.stockKline("茅台", "30");

            assertThat(result).startsWith("[stock_kline] 参数错误");
            verify(preloader, never()).buildKlineContext(anyString(), anyInt());
        }

        @Test
        @DisplayName("stock_kline：days 非数字用默认 120 并调用 preloader")
        void klineDefaultsDays() {
            StockContextPreloader preloader = mock(StockContextPreloader.class);
            when(preloader.buildKlineContext("600519", StockKlineTool.DEFAULT_DAYS)).thenReturn("K线摘要");
            StockKlineTool tool = new StockKlineTool(preloader);

            assertThat(tool.stockKline("600519", "很多天")).isEqualTo("K线摘要");
            verify(preloader).buildKlineContext(eq("600519"), eq(StockKlineTool.DEFAULT_DAYS));
        }

        @Test
        @DisplayName("stock_kline：preloader 异常降级为错误文本，不上抛")
        void klineSwallowsException() {
            StockContextPreloader preloader = mock(StockContextPreloader.class);
            when(preloader.buildKlineContext(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("db down"));
            StockKlineTool tool = new StockKlineTool(preloader);

            assertThat(tool.stockKline("600519", null)).startsWith("[stock_kline] 查询失败");
        }
    }

    // ─────────────────── DataEnrichTool.buildDirectionKey ───────────────────

    @Nested
    @DisplayName("data_enrich 缓存 key 截断")
    class DirectionKey {

        @Test
        @DisplayName("短主题：前缀 + 原文")
        void shortTopic() {
            assertThat(DataEnrichTool.buildDirectionKey("近期舆情")).isEqualTo("WF_ENRICH_近期舆情");
        }

        @Test
        @DisplayName("超长主题：截断到 38 字符，总长不超过 direction_key 列宽 50")
        void longTopicTruncated() {
            String topic = "超".repeat(60);
            String key = DataEnrichTool.buildDirectionKey(topic);
            assertThat(key).isEqualTo("WF_ENRICH_" + "超".repeat(38));
            assertThat(key.length()).isLessThanOrEqualTo(50);
        }
    }
}
