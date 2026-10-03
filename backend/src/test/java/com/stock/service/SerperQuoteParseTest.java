package com.stock.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SerperSearchService.parseQuote 行情文本解析单测。
 * 覆盖 Google 行情卡（answerBox）与 A 股搜索摘要（雪球/Yahoo/新浪）的真实返回格式。
 */
class SerperQuoteParseTest {

    @Test
    void parsesUsAnswerBoxFormat() {
        // Google 行情卡 answerBox.answer 实测格式（AAPL）
        SerperSearchService.QuoteResult r = SerperSearchService.parseQuote("313.33 +0.92 (0.29%)");
        assertThat(r).isNotNull();
        assertThat(r.price()).isEqualByComparingTo(new BigDecimal("313.33"));
        assertThat(r.change()).isEqualByComparingTo(new BigDecimal("0.92"));
        assertThat(r.changePct()).isEqualByComparingTo(new BigDecimal("0.29"));
    }

    @Test
    void parsesAXueqiuSnippetFormat() {
        // 雪球 A 股摘要实测格式（600519 贵州茅台）
        SerperSearchService.QuoteResult r = SerperSearchService.parseQuote(
                "贵州茅台. 1309.22. +0.67 +0.05%. SH600519, 08-07 15:00:00（北京时间）.");
        assertThat(r).isNotNull();
        assertThat(r.price()).isEqualByComparingTo(new BigDecimal("1309.22"));
        assertThat(r.changePct()).isEqualByComparingTo(new BigDecimal("0.05"));
    }

    @Test
    void parsesYahooThousandSeparatorFormat() {
        // Yahoo 财经千分位格式
        SerperSearchService.QuoteResult r = SerperSearchService.parseQuote(
                "中國貴州茅台酒廠(集團)有限責任公司(600519.SS). 1,309.22 +0.67 (+0.05%). 收市");
        assertThat(r).isNotNull();
        assertThat(r.price()).isEqualByComparingTo(new BigDecimal("1309.22"));
    }

    @Test
    void parsesNegativeChange() {
        SerperSearchService.QuoteResult r = SerperSearchService.parseQuote("38.05 -1.20 (-3.06%)");
        assertThat(r).isNotNull();
        assertThat(r.change()).isEqualByComparingTo(new BigDecimal("-1.20"));
        assertThat(r.changePct()).isEqualByComparingTo(new BigDecimal("-3.06"));
    }

    @Test
    void returnsNullForNonQuoteText() {
        assertThat(SerperSearchService.parseQuote(null)).isNull();
        assertThat(SerperSearchService.parseQuote("")).isNull();
        // 新浪"今开/换手"片段不含 价格+涨跌额+涨跌幅 结构，不应误判
        assertThat(SerperSearchService.parseQuote("今开: 1299.00. 换手: 0.43%. 总值 ; 最高: 1320.00")).isNull();
        assertThat(SerperSearchService.parseQuote("纯文字没有任何数字")).isNull();
    }
}
