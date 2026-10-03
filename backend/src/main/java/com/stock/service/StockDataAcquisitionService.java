package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.entity.StockBasic;
import com.stock.entity.StockFinance;
import com.stock.entity.StockFundFlow;
import com.stock.entity.StockKlineDaily;
import com.stock.entity.StockQuote;
import com.stock.repository.StockBasicRepository;
import com.stock.repository.StockFinanceRepository;
import com.stock.repository.StockFundFlowRepository;
import com.stock.repository.StockKlineDailyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 个股结构化数据自动拉取服务（东方财富接口，契约与 data-fetcher/import_stock.py、
 * import_fundflow.py 完全一致）：实时行情+基本信息 / 120 日日K / 8 期财务 / 60 日资金流。
 * <p>
 * 定位：股票分析工作流的"数据准备前置"闸门。本地库缺数据时自动补齐，
 * 避免 11 个 LLM 节点在数据真空上空跑烧 token。
 * <p>
 * 与 Serper 的分工：**实时行情首选 Serper**（Google 行情卡/搜索摘要解析，
 * 见 {@link SerperSearchService#fetchQuote}），未命中才降级东财；**日K线走
 * {@link KimiKlineService}（腾讯直连→新浪→Kimi 兜底）**，不再依赖东财；
 * 财务/资金流等其余结构化时序仍走东财；行业/舆情等定性信息由 web_search/data_enrich 提供。
 * <p>
 * 限流：外部调用之间固定间隔 {@code stock-data.acquire.interval-ms}（缺省 5s，与 Python 脚本一致）。
 */
@Service
public class StockDataAcquisitionService {

    private static final Logger log = LoggerFactory.getLogger(StockDataAcquisitionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUOTE_API = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String FINANCE_API = "https://datacenter.eastmoney.com/securities/api/data/v1/get";
    private static final String FFLOW_API = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    /** K线少于此条数视为"不可分析"（技术面/筹码面需要足够样本） */
    public static final int MIN_KLINES_FOR_TRADEABLE = 10;

    /** 最新K线距今超过此自然日数视为"过期"，需重拉补齐（7天覆盖周末+长假） */
    public static final int KLINE_STALE_DAYS = 7;

    private final StockDataService stockDataService;
    private final StockBasicRepository basicRepo;
    private final StockKlineDailyRepository klineRepo;
    private final StockFinanceRepository financeRepo;
    private final StockFundFlowRepository fundFlowRepo;
    private final SerperSearchService serperSearchService;
    private final KimiKlineService kimiKlineService;
    private final RestTemplate restTemplate;
    private final long intervalMs;

    public StockDataAcquisitionService(StockDataService stockDataService,
                                       StockBasicRepository basicRepo,
                                       StockKlineDailyRepository klineRepo,
                                       StockFinanceRepository financeRepo,
                                       StockFundFlowRepository fundFlowRepo,
                                       SerperSearchService serperSearchService,
                                       KimiKlineService kimiKlineService,
                                       @Value("${stock-data.acquire.interval-ms:5000}") long intervalMs) {
        this.stockDataService = stockDataService;
        this.basicRepo = basicRepo;
        this.klineRepo = klineRepo;
        this.financeRepo = financeRepo;
        this.fundFlowRepo = fundFlowRepo;
        this.serperSearchService = serperSearchService;
        this.kimiKlineService = kimiKlineService;
        this.intervalMs = intervalMs;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 数据准备结果：tradeable=false 时调用方应阻断工作流启动（快速失败，不烧 token） */
    public record AcquisitionResult(boolean tradeable, long klineCount, long financeCount,
                                    long fundflowCount, String detail) {}

    /**
     * 确保个股核心数据就绪：先盘点本地库，缺什么补什么（行情/K线/财务/资金流）。
     * 单步拉取失败只记录不中断后续步骤；最终以 K线条数判定是否可分析。
     */
    public AcquisitionResult ensureStockData(String code) {
        long kline0 = klineRepo.countByCode(code);
        long finance0 = financeRepo.countByCode(code);
        long fundflow0 = fundFlowRepo.countByCode(code);
        boolean basicMissing = basicRepo.findByCode(code).isEmpty();
        // 新鲜度：最新K线日期距今超过 KLINE_STALE_DAYS 视为过期（有量但太旧，分析会基于过时行情）
        LocalDate latestKline = klineRepo.findTop1ByCodeOrderByTradeDateDesc(code)
                .map(StockKlineDaily::getTradeDate).orElse(null);
        boolean klineStale = latestKline != null
                && latestKline.isBefore(LocalDate.now().minusDays(KLINE_STALE_DAYS));

        if (kline0 >= MIN_KLINES_FOR_TRADEABLE && !klineStale && finance0 > 0 && fundflow0 > 0 && !basicMissing) {
            return new AcquisitionResult(true, kline0, finance0, fundflow0,
                    "本地数据完备，无需拉取");
        }

        log.info("[数据准备] {} 本地盘点: K线={} 最新={}{} 财务={} 资金流={} 基本信息={}，开始补齐拉取",
                code, kline0, latestKline, klineStale ? "(过期)" : "", finance0, fundflow0,
                basicMissing ? "缺失" : "已有");
        List<String> steps = new ArrayList<>();
        String nameHint = basicRepo.findByCode(code).map(StockBasic::getName).orElse(null);

        // ① 实时行情 + 基本信息（K线缺失或基本信息缺失时拉，行情 Serper 首选、东财降级）
        if (kline0 == 0 || basicMissing) {
            steps.add(fetchQuoteAndBasic(code, nameHint));
            throttle();
        }
        // ② 日K线（量化分析的地基，缺失或过期必拉；saveKlines 按日期 upsert，重拉幂等）
        if (kline0 < MIN_KLINES_FOR_TRADEABLE || klineStale) {
            steps.add(fetchKline(code));
            throttle();
        }
        // ③ 财务（缺失则拉；失败不阻断，基本面节点可降级）
        if (finance0 == 0) {
            steps.add(fetchFinance(code));
            throttle();
        }
        // ④ 资金流（缺失则拉；失败不阻断，资金面节点可降级）
        if (fundflow0 == 0) {
            steps.add(fetchFundFlow(code));
        }

        long kline1 = klineRepo.countByCode(code);
        long finance1 = financeRepo.countByCode(code);
        long fundflow1 = fundFlowRepo.countByCode(code);
        boolean tradeable = kline1 >= MIN_KLINES_FOR_TRADEABLE;
        String detail = String.join("；", steps);
        log.info("[数据准备] {} 完成: K线={} 财务={} 资金流={} tradeable={} | {}",
                code, kline1, finance1, fundflow1, tradeable, detail);
        return new AcquisitionResult(tradeable, kline1, finance1, fundflow1, detail);
    }

    // ==================== 各数据域拉取（失败返回"[X] ..."文本，不抛异常） ====================

    private String fetchQuoteAndBasic(String code, String nameHint) {
        // 行情首选通道：Serper（Google 行情卡/搜索摘要解析现价）
        SerperSearchService.QuoteResult q = serperSearchService.fetchQuote(code, nameHint);
        if (q != null) {
            // 仅在已知真实名称时补 basic（Serper 拿不到名称，占位名会污染后续查询；未知时留给东财 f58）
            if (nameHint != null && !nameHint.isBlank() && basicRepo.findByCode(code).isEmpty()) {
                stockDataService.saveBasic(new StockBasic(code, nameHint,
                        code.startsWith("6") ? "SH" : "SZ", null, null));
            }
            StockQuote quote = new StockQuote();
            quote.setCode(code);
            quote.setPrice(q.price());
            quote.setChangePct(q.changePct());
            if (q.change() != null) {
                quote.setPreClose(q.price().subtract(q.change()));
            }
            stockDataService.saveQuote(quote);
            return "[OK] 行情(Serper): 现价 " + q.price() + " 涨跌幅 " + q.changePct() + "%";
        }
        // Serper 未命中 → 东财降级（全字段行情：开高低/量额）
        return fetchQuoteFromEastmoney(code);
    }

    private String fetchQuoteFromEastmoney(String code) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(QUOTE_API)
                    .queryParam("secid", secid(code))
                    .queryParam("fields", "f43,f44,f45,f46,f47,f48,f57,f58,f60,f170")
                    .build().toUriString();
            JsonNode d = MAPPER.readTree(get(url, "https://emweb.securities.eastmoney.com/")).path("data");
            if (d.isMissingNode() || d.isNull() || d.isEmpty()) {
                return "[X] 行情接口返回空（代码可能无效）: " + code;
            }
            String name = d.path("f58").asText(code);
            var basicOpt = basicRepo.findByCode(code);
            if (basicOpt.isEmpty()) {
                StockBasic basic = new StockBasic(code, name,
                        code.startsWith("6") ? "SH" : "SZ", null, null);
                stockDataService.saveBasic(basic);
            } else if (!name.equals(code) && !name.equals(basicOpt.get().getName())) {
                // 自愈：覆盖旧占位名（如曾以代码占位）为东财真实名称
                StockBasic basic = basicOpt.get();
                basic.setName(name);
                stockDataService.saveBasic(basic);
            }
            StockQuote quote = new StockQuote();
            quote.setCode(code);
            quote.setPrice(px(d.path("f43")));
            quote.setOpen(px(d.path("f46")));
            quote.setHigh(px(d.path("f44")));
            quote.setLow(px(d.path("f45")));
            quote.setPreClose(px(d.path("f60")));
            quote.setVolume(d.path("f47").canConvertToLong() ? d.path("f47").asLong() : null);
            quote.setAmount(dec(d.path("f48")));
            quote.setChangePct(div100(d.path("f170")));
            stockDataService.saveQuote(quote);
            return "[OK] 行情+基本信息: " + name + " 现价 " + quote.getPrice();
        } catch (Exception e) {
            return "[X] 行情拉取失败: " + e.getMessage();
        }
    }

    private String fetchKline(String code) {
        // 腾讯直连→新浪→Kimi 兜底（东财 push2his 频繁掉线，已不再是K线数据源）
        List<StockKlineDaily> rows = kimiKlineService.fetchKlines(code, 120);
        if (rows.isEmpty()) {
            return "[X] K线拉取失败（腾讯/新浪/Kimi 均未命中）: " + code;
        }
        try {
            stockDataService.saveKlines(code, rows);
            return "[OK] K线 " + rows.size() + " 条 ("
                    + rows.get(0).getTradeDate() + " ~ " + rows.get(rows.size() - 1).getTradeDate() + ")";
        } catch (Exception e) {
            return "[X] K线入库失败: " + e.getMessage();
        }
    }

    private String fetchFinance(String code) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(FINANCE_API)
                    .queryParam("reportName", "RPT_LICO_FN_CPD")
                    .queryParam("columns", "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORTDATE")
                    .queryParam("filter", "(SECURITY_CODE=\"" + code + "\")")
                    .queryParam("pageSize", 8)
                    .queryParam("sortColumns", "NOTICE_DATE")
                    .queryParam("sortTypes", -1)
                    .build().toUriString();
            JsonNode rows = MAPPER.readTree(get(url, "https://data.eastmoney.com/")).path("result").path("data");
            if (!rows.isArray() || rows.isEmpty()) {
                return "[X] 财务接口返回空: " + code;
            }
            List<StockFinance> finances = new ArrayList<>();
            for (JsonNode row : rows) {
                // 2026-04 起东财字段由 REPORT_DATE 更名为 REPORTDATE，双向兼容
                String rd = firstNonBlank(row.path("REPORTDATE").asText(""),
                        row.path("REPORT_DATE").asText(""));
                if (rd.length() < 10) {
                    continue;
                }
                StockFinance f = new StockFinance();
                f.setReportDate(LocalDate.parse(rd.substring(0, 10)));
                f.setBasicEps(dec(row.path("BASIC_EPS")));
                f.setWeightedRoe(dec(row.path("WEIGHTAVG_ROE")));
                f.setTotalRevenue(dec(row.path("TOTAL_OPERATE_INCOME")));
                f.setNetProfit(dec(row.path("PARENT_NETPROFIT")));
                finances.add(f);
            }
            stockDataService.saveFinances(code, finances);
            return "[OK] 财务 " + finances.size() + " 期";
        } catch (Exception e) {
            return "[X] 财务拉取失败: " + e.getMessage();
        }
    }

    private String fetchFundFlow(String code) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(FFLOW_API)
                    .queryParam("secid", secid(code))
                    .queryParam("fields1", "f1,f2,f3,f7")
                    .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                    .queryParam("klt", 101).queryParam("lmt", 60)
                    .build().toUriString();
            JsonNode klines = MAPPER.readTree(get(url, "https://data.eastmoney.com/"))
                    .path("data").path("klines");
            if (!klines.isArray() || klines.isEmpty()) {
                return "[X] 资金流接口返回空: " + code;
            }
            List<StockFundFlow> flows = new ArrayList<>();
            for (JsonNode line : klines) {
                String[] p = line.asText().split(",");
                if (p.length < 6) {
                    continue;
                }
                StockFundFlow f = new StockFundFlow();
                f.setTradeDate(LocalDate.parse(p[0]));
                f.setMainNetInflow(decStr(p[1]));
                f.setSmallNetInflow(decStr(p[2]));
                f.setMediumNetInflow(decStr(p[3]));
                f.setLargeNetInflow(decStr(p[4]));
                f.setSuperLargeNetInflow(decStr(p[5]));
                f.setMainNetRatio(p.length > 6 ? decStr(p[6]) : null);
                flows.add(f);
            }
            stockDataService.saveFundFlows(code, flows);
            return "[OK] 资金流 " + flows.size() + " 条";
        } catch (Exception e) {
            return "[X] 资金流拉取失败: " + e.getMessage();
        }
    }

    // ==================== 工具方法 ====================

    private String get(String url, String referer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", referer);
        headers.set("User-Agent", UA);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }

    /** 东财 secid：沪市（6 开头）前缀 1，深市前缀 0 */
    private static String secid(String code) {
        return code.startsWith("6") ? "1." + code : "0." + code;
    }

    /** 东财价格字段需除以 100 的归一化（与 import_stock.py 的 px() 完全一致） */
    private static BigDecimal px(JsonNode node) {
        BigDecimal v = dec(node);
        if (v == null) {
            return null;
        }
        return v.abs().compareTo(BigDecimal.valueOf(100)) > 0
                ? v.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP) : v;
    }

    private static BigDecimal div100(JsonNode node) {
        BigDecimal v = dec(node);
        return v == null ? null : v.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal dec(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return decStr(node.asText(""));
    }

    private static BigDecimal decStr(String s) {
        if (s == null || s.isBlank() || "-".equals(s.trim())) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null ? b : "");
    }

    /** 外部接口限流间隔（与 data-fetcher 的 REQUEST_INTERVAL=5s 对齐） */
    private void throttle() {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
