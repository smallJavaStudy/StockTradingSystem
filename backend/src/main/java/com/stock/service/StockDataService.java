package com.stock.service;

import com.stock.entity.*;
import com.stock.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class StockDataService {

    @Autowired private StockBasicRepository basicRepo;
    @Autowired private StockQuoteRepository quoteRepo;
    @Autowired private StockKlineDailyRepository klineRepo;
    @Autowired private StockFinanceRepository financeRepo;
    @Autowired private StockFundFlowRepository fundFlowRepo;

    // ==================== 股票基本信息 ====================

    public StockBasic saveBasic(StockBasic basic) {
        return basicRepo.findByCode(basic.getCode())
                .map(existing -> { basic.setId(existing.getId()); return basicRepo.save(basic); })
                .orElseGet(() -> basicRepo.save(basic));
    }

    public Optional<StockBasic> getBasic(String code) {
        return basicRepo.findByCode(code);
    }

    public List<StockBasic> getAllBasics() {
        return basicRepo.findAll();
    }

    // ==================== 行情数据 ====================

    @Transactional
    public StockQuote saveQuote(StockQuote quote) {
        return quoteRepo.save(quote);
    }

    public Optional<StockQuote> getLatestQuote(String code) {
        return quoteRepo.findTopByCodeOrderByUpdateTimeDesc(code);
    }

    // ==================== K线数据 ====================

    @Transactional
    public List<StockKlineDaily> saveKlines(String code, List<StockKlineDaily> klines) {
        List<StockKlineDaily> saved = new ArrayList<>();
        for (StockKlineDaily kline : klines) {
            kline.setCode(code);
            klineRepo.findByCodeAndTradeDate(code, kline.getTradeDate())
                    .ifPresentOrElse(
                            existing -> { kline.setId(existing.getId()); saved.add(klineRepo.save(kline)); },
                            () -> saved.add(klineRepo.save(kline))
                    );
        }
        return saved;
    }

    public List<StockKlineDaily> getKlines(String code) {
        return klineRepo.findByCodeOrderByTradeDateDesc(code);
    }

    // ==================== 基本面数据 ====================

    @Transactional
    public List<StockFinance> saveFinances(String code, List<StockFinance> finances) {
        List<StockFinance> saved = new ArrayList<>();
        for (StockFinance finance : finances) {
            finance.setCode(code);
            financeRepo.findByCodeAndReportDate(code, finance.getReportDate())
                    .ifPresentOrElse(
                            existing -> { finance.setId(existing.getId()); saved.add(financeRepo.save(finance)); },
                            () -> saved.add(financeRepo.save(finance))
                    );
        }
        return saved;
    }

    public List<StockFinance> getFinances(String code) {
        return financeRepo.findByCodeOrderByReportDateDesc(code);
    }

    // ==================== 资金流数据 ====================

    @Transactional
    public List<StockFundFlow> saveFundFlows(String code, List<StockFundFlow> flows) {
        List<StockFundFlow> saved = new ArrayList<>();
        for (StockFundFlow flow : flows) {
            flow.setCode(code);
            fundFlowRepo.findByCodeAndTradeDate(code, flow.getTradeDate())
                    .ifPresentOrElse(
                            existing -> { flow.setId(existing.getId()); saved.add(fundFlowRepo.save(flow)); },
                            () -> saved.add(fundFlowRepo.save(flow))
                    );
        }
        return saved;
    }

    public List<StockFundFlow> getFundFlows(String code) {
        return fundFlowRepo.findTop60ByCodeOrderByTradeDateDesc(code);
    }

    // ==================== 综合查询 ====================

    public Map<String, Object> getStockFullData(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("basic", getBasic(code).orElse(null));
        result.put("quote", getLatestQuote(code).orElse(null));
        result.put("klines", getKlines(code));
        result.put("finances", getFinances(code));
        return result;
    }
}
