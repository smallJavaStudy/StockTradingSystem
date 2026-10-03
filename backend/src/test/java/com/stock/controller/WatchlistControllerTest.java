package com.stock.controller;

import com.stock.controller.WatchlistController.WatchlistQuoteView;
import com.stock.entity.StockKlineDaily;
import com.stock.entity.StockQuote;
import com.stock.entity.Watchlist;
import com.stock.repository.StockKlineDailyRepository;
import com.stock.repository.StockQuoteRepository;
import com.stock.repository.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link WatchlistController} 单元测试：mock {@link WatchlistRepository}（不依赖数据库），
 * 覆盖列表查询（含行情速览内联）、添加（含 stockCode 去重与必填校验）、更新（部分字段 + 改码去重）、删除。
 */
class WatchlistControllerTest {

    private WatchlistRepository repo;
    private StockQuoteRepository quoteRepo;
    private StockKlineDailyRepository klineRepo;
    private WatchlistController controller;

    private Watchlist saved;

    @BeforeEach
    void setUp() {
        repo = mock(WatchlistRepository.class);
        quoteRepo = mock(StockQuoteRepository.class);
        klineRepo = mock(StockKlineDailyRepository.class);
        controller = new WatchlistController(repo, quoteRepo, klineRepo);

        saved = new Watchlist("600519", "贵州茅台", "白酒", "核心资产", "长期持有");
        saved.setId(1L);

        // save 直接回显入参
        when(repo.save(any(Watchlist.class))).thenAnswer(inv -> inv.getArgument(0));
        // 行情默认无数据（行情相关用例内另行 stub）
        when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc(anyString())).thenReturn(Optional.empty());
        when(klineRepo.findTop2ByCodeOrderByTradeDateDesc(anyString())).thenReturn(List.of());
    }

    // ─────────────────── 查询 ───────────────────

    @Test
    @DisplayName("GET：按分组+创建时间排序返回全部自选股（无行情时行情字段置 null 不报错）")
    void listAll_returnsOrderedList() {
        when(repo.findAllByOrderByGroupNameAscCreatedAtDesc()).thenReturn(List.of(saved));

        List<WatchlistQuoteView> result = controller.listAll().getBody();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stockCode()).isEqualTo("600519");
        assertThat(result.get(0).latestPrice()).isNull();
        assertThat(result.get(0).changePct()).isNull();
        assertThat(result.get(0).quoteDate()).isNull();
        verify(repo).findAllByOrderByGroupNameAscCreatedAtDesc();
    }

    // ─────────────────── 行情速览内联 ───────────────────

    @Nested
    @DisplayName("GET 行情速览内联")
    class QuoteInline {

        @BeforeEach
        void stubList() {
            when(repo.findAllByOrderByGroupNameAscCreatedAtDesc()).thenReturn(List.of(saved));
        }

        @Test
        @DisplayName("stock_quote 有最新行情：直接内联现价/涨跌幅，不再查日K")
        void listAll_inlinesLatestQuote() {
            StockQuote quote = new StockQuote();
            quote.setCode("600519");
            quote.setPrice(new BigDecimal("1502.30"));
            quote.setChangePct(new BigDecimal("-1.25"));
            quote.setUpdateTime(LocalDateTime.of(2026, 7, 29, 15, 5));
            when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc("600519")).thenReturn(Optional.of(quote));

            WatchlistQuoteView view = controller.listAll().getBody().get(0);

            assertThat(view.latestPrice()).isEqualByComparingTo("1502.30");
            assertThat(view.changePct()).isEqualByComparingTo("-1.25");
            assertThat(view.quoteDate()).isEqualTo("2026-07-29");
            verify(klineRepo, never()).findTop2ByCodeOrderByTradeDateDesc(anyString());
        }

        @Test
        @DisplayName("无 stock_quote：退化用日K最新收盘价，涨跌幅由前一日收盘推算（HALF_UP 保留 2 位）")
        void listAll_fallsBackToKline() {
            StockKlineDaily today = kline("600519", LocalDate.of(2026, 7, 29), "11.00");
            StockKlineDaily prev = kline("600519", LocalDate.of(2026, 7, 28), "10.00");
            when(klineRepo.findTop2ByCodeOrderByTradeDateDesc("600519")).thenReturn(List.of(today, prev));

            WatchlistQuoteView view = controller.listAll().getBody().get(0);

            assertThat(view.latestPrice()).isEqualByComparingTo("11.00");
            assertThat(view.changePct()).isEqualByComparingTo("10.00");
            assertThat(view.quoteDate()).isEqualTo("2026-07-29");
        }

        @Test
        @DisplayName("日K仅一条（无前收盘）：有最新价但涨跌幅置 null")
        void listAll_singleKline_noChangePct() {
            StockKlineDaily only = kline("600519", LocalDate.of(2026, 7, 29), "11.00");
            when(klineRepo.findTop2ByCodeOrderByTradeDateDesc("600519")).thenReturn(List.of(only));

            WatchlistQuoteView view = controller.listAll().getBody().get(0);

            assertThat(view.latestPrice()).isEqualByComparingTo("11.00");
            assertThat(view.changePct()).isNull();
        }

        @Test
        @DisplayName("行情查询抛异常：列表不报错，行情字段置 null")
        void listAll_quoteQueryError_returnsNullFields() {
            when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc("600519"))
                    .thenThrow(new RuntimeException("db down"));

            WatchlistQuoteView view = controller.listAll().getBody().get(0);

            assertThat(view.stockCode()).isEqualTo("600519");
            assertThat(view.latestPrice()).isNull();
            assertThat(view.changePct()).isNull();
        }

        private StockKlineDaily kline(String code, LocalDate date, String close) {
            StockKlineDaily k = new StockKlineDaily();
            k.setCode(code);
            k.setTradeDate(date);
            k.setClose(new BigDecimal(close));
            return k;
        }
    }

    // ─────────────────── 添加 ───────────────────

    @Nested
    @DisplayName("POST 添加")
    class Add {

        @Test
        @DisplayName("正常添加：保存并回填默认分组")
        void add_success_withDefaultGroup() {
            when(repo.existsByStockCode("300364")).thenReturn(false);
            Watchlist input = new Watchlist();
            input.setStockCode("300364");
            input.setStockName("中文在线");

            Watchlist result = controller.add(input).getBody();

            assertThat(result.getGroupName()).isEqualTo("默认分组");
            ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().getStockCode()).isEqualTo("300364");
        }

        @Test
        @DisplayName("stockCode 重复：抛 IllegalArgumentException，不落库")
        void add_duplicateCode_throws() {
            when(repo.existsByStockCode("600519")).thenReturn(true);
            Watchlist input = new Watchlist();
            input.setStockCode("600519");
            input.setStockName("贵州茅台");

            assertThatThrownBy(() -> controller.add(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已在自选股中");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("代码/名称为空：抛 IllegalArgumentException")
        void add_blankFields_throws() {
            Watchlist noCode = new Watchlist();
            noCode.setStockName("某股");
            assertThatThrownBy(() -> controller.add(noCode))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("股票代码不能为空");

            Watchlist noName = new Watchlist();
            noName.setStockCode("000001");
            assertThatThrownBy(() -> controller.add(noName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("股票名称不能为空");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("客户端传入 id 被忽略（防止误更新）")
        void add_ignoresClientId() {
            when(repo.existsByStockCode("300058")).thenReturn(false);
            Watchlist input = new Watchlist();
            input.setId(99L);
            input.setStockCode("300058");
            input.setStockName("蓝色光标");

            controller.add(input);

            ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().getId()).isNull();
        }
    }

    // ─────────────────── 更新 ───────────────────

    @Nested
    @DisplayName("PUT 更新")
    class Update {

        @Test
        @DisplayName("部分字段更新：仅覆盖非空字段，tags 可置空串")
        void update_partialFields() {
            when(repo.findById(1L)).thenReturn(Optional.of(saved));
            Watchlist patch = new Watchlist();
            patch.setGroupName("消费");
            patch.setTags("");

            Watchlist result = controller.update(1L, patch).getBody();

            assertThat(result.getGroupName()).isEqualTo("消费");
            assertThat(result.getTags()).isEmpty();
            assertThat(result.getStockCode()).isEqualTo("600519"); // 未变
            assertThat(result.getStockName()).isEqualTo("贵州茅台"); // 未变
        }

        @Test
        @DisplayName("修改 stockCode 为已存在代码：抛 IllegalArgumentException")
        void update_changeToDuplicateCode_throws() {
            when(repo.findById(1L)).thenReturn(Optional.of(saved));
            when(repo.existsByStockCode("000001")).thenReturn(true);
            Watchlist patch = new Watchlist();
            patch.setStockCode("000001");

            assertThatThrownBy(() -> controller.update(1L, patch))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已在自选股中");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("记录不存在：抛 NoSuchElementException")
        void update_notFound_throws() {
            when(repo.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(404L, new Watchlist()))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    // ─────────────────── 删除 ───────────────────

    @Nested
    @DisplayName("DELETE 删除")
    class Delete {

        @Test
        @DisplayName("正常删除：返回 204")
        void delete_success() {
            when(repo.existsById(1L)).thenReturn(true);

            var response = controller.delete(1L);

            assertThat(response.getStatusCode().value()).isEqualTo(204);
            verify(repo).deleteById(1L);
        }

        @Test
        @DisplayName("记录不存在：抛 NoSuchElementException，不执行删除")
        void delete_notFound_throws() {
            when(repo.existsById(404L)).thenReturn(false);

            assertThatThrownBy(() -> controller.delete(404L))
                    .isInstanceOf(NoSuchElementException.class);
            verify(repo, never()).deleteById(any());
        }
    }
}
