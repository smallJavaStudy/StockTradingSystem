package com.stock.service;

import com.stock.entity.PriceAlert;
import com.stock.entity.StockQuote;
import com.stock.repository.PriceAlertRepository;
import com.stock.repository.StockQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link AlertScanService} 单元测试：mock 两个 Repository，
 * 覆盖命中判定（PRICE_ABOVE/PRICE_BELOW 含边界等值）与扫描触发流程（缺行情跳过、行情缓存去重）。
 */
class AlertScanServiceTest {

    private PriceAlertRepository alertRepo;
    private StockQuoteRepository quoteRepo;
    private AlertScanService service;

    @BeforeEach
    void setUp() {
        alertRepo = mock(PriceAlertRepository.class);
        quoteRepo = mock(StockQuoteRepository.class);
        service = new AlertScanService(alertRepo, quoteRepo);
    }

    private PriceAlert alert(String code, PriceAlert.AlertType type, String threshold) {
        return new PriceAlert(code, type, new BigDecimal(threshold));
    }

    private StockQuote quote(String code, String price) {
        StockQuote q = new StockQuote();
        q.setCode(code);
        q.setPrice(new BigDecimal(price));
        return q;
    }

    // ─────────────────── 命中判定 ───────────────────

    @Test
    @DisplayName("PRICE_ABOVE：现价≥阈值命中（含等值），低于不命中")
    void isHit_priceAbove() {
        PriceAlert a = alert("300364", PriceAlert.AlertType.PRICE_ABOVE, "20.00");

        assertThat(service.isHit(a, new BigDecimal("20.01"))).isTrue();
        assertThat(service.isHit(a, new BigDecimal("20.00"))).isTrue();  // 边界等值
        assertThat(service.isHit(a, new BigDecimal("19.99"))).isFalse();
    }

    @Test
    @DisplayName("PRICE_BELOW：现价≤阈值命中（含等值），高于不命中")
    void isHit_priceBelow() {
        PriceAlert a = alert("300364", PriceAlert.AlertType.PRICE_BELOW, "15.00");

        assertThat(service.isHit(a, new BigDecimal("14.99"))).isTrue();
        assertThat(service.isHit(a, new BigDecimal("15.00"))).isTrue();  // 边界等值
        assertThat(service.isHit(a, new BigDecimal("15.01"))).isFalse();
    }

    // ─────────────────── 扫描流程 ───────────────────

    @Test
    @DisplayName("扫描命中：置 triggered=true 并记录触发时间后保存")
    void scanOnce_hit_marksTriggered() {
        PriceAlert a = alert("300364", PriceAlert.AlertType.PRICE_ABOVE, "20.00");
        when(alertRepo.findByEnabledTrueAndTriggeredFalse()).thenReturn(List.of(a));
        when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc("300364"))
                .thenReturn(Optional.of(quote("300364", "21.50")));

        int triggered = service.scanOnce();

        assertThat(triggered).isEqualTo(1);
        ArgumentCaptor<PriceAlert> captor = ArgumentCaptor.forClass(PriceAlert.class);
        verify(alertRepo).save(captor.capture());
        assertThat(captor.getValue().getTriggered()).isTrue();
        assertThat(captor.getValue().getTriggeredAt()).isNotNull();
    }

    @Test
    @DisplayName("扫描未命中：不保存、返回 0")
    void scanOnce_miss_noSave() {
        PriceAlert a = alert("300364", PriceAlert.AlertType.PRICE_ABOVE, "99.00");
        when(alertRepo.findByEnabledTrueAndTriggeredFalse()).thenReturn(List.of(a));
        when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc("300364"))
                .thenReturn(Optional.of(quote("300364", "21.50")));

        assertThat(service.scanOnce()).isZero();
        verify(alertRepo, never()).save(any());
    }

    @Test
    @DisplayName("无行情数据的股票跳过，不影响其他预警触发")
    void scanOnce_missingQuote_skipped() {
        PriceAlert noQuote = alert("000404", PriceAlert.AlertType.PRICE_BELOW, "10.00");
        PriceAlert hit = alert("300364", PriceAlert.AlertType.PRICE_ABOVE, "20.00");
        when(alertRepo.findByEnabledTrueAndTriggeredFalse()).thenReturn(List.of(noQuote, hit));
        when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc("000404")).thenReturn(Optional.empty());
        when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc("300364"))
                .thenReturn(Optional.of(quote("300364", "25.00")));

        assertThat(service.scanOnce()).isEqualTo(1);
        assertThat(noQuote.getTriggered()).isFalse();
        assertThat(hit.getTriggered()).isTrue();
    }

    @Test
    @DisplayName("同一股票多条预警：行情只查一次（缓存去重）")
    void scanOnce_sameCode_quoteQueriedOnce() {
        PriceAlert a1 = alert("300364", PriceAlert.AlertType.PRICE_ABOVE, "20.00");
        PriceAlert a2 = alert("300364", PriceAlert.AlertType.PRICE_BELOW, "30.00");
        when(alertRepo.findByEnabledTrueAndTriggeredFalse()).thenReturn(List.of(a1, a2));
        when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc("300364"))
                .thenReturn(Optional.of(quote("300364", "25.00")));

        service.scanOnce();

        verify(quoteRepo, times(1)).findTopByCodeOrderByUpdateTimeDesc("300364");
    }

    @Test
    @DisplayName("无待扫描预警：不查询行情直接返回 0")
    void scanOnce_empty_returnsZero() {
        when(alertRepo.findByEnabledTrueAndTriggeredFalse()).thenReturn(List.of());

        assertThat(service.scanOnce()).isZero();
        verify(quoteRepo, never()).findTopByCodeOrderByUpdateTimeDesc(anyString());
    }
}
