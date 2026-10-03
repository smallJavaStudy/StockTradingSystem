package com.stock.repository;

import com.stock.entity.StockMarginDaily;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockMarginDailyRepository extends JpaRepository<StockMarginDaily, Long> {

    List<StockMarginDaily> findByOrderByTradeDateDescMarketAsc(Pageable pageable);

    List<StockMarginDaily> findByMarketOrderByTradeDateDesc(String market, Pageable pageable);

    Optional<StockMarginDaily> findByTradeDateAndMarket(LocalDate tradeDate, String market);

    /** 最近 N 个有数据的交易日（倒序） */
    @Query("select distinct m.tradeDate from StockMarginDaily m order by m.tradeDate desc")
    List<LocalDate> findDistinctTradeDates(Pageable pageable);

    List<StockMarginDaily> findByTradeDateInOrderByTradeDateDescMarketAsc(List<LocalDate> tradeDates);
}
