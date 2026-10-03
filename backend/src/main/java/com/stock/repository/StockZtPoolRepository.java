package com.stock.repository;

import com.stock.entity.StockZtPool;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockZtPoolRepository extends JpaRepository<StockZtPool, Long> {

    List<StockZtPool> findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(LocalDate tradeDate, String poolType);

    List<StockZtPool> findByTradeDateOrderByLimitUpDaysDescChangePctDesc(LocalDate tradeDate);

    List<StockZtPool> findByCodeOrderByTradeDateDesc(String code);

    Optional<StockZtPool> findByCodeAndTradeDateAndPoolType(String code, LocalDate tradeDate, String poolType);

    /** 最新有数据的交易日 */
    @Query("select max(z.tradeDate) from StockZtPool z")
    Optional<LocalDate> findMaxTradeDate();

    /** 最近 N 个有数据的交易日（倒序），N 由 Pageable 控制 */
    @Query("select distinct z.tradeDate from StockZtPool z order by z.tradeDate desc")
    List<LocalDate> findDistinctTradeDates(Pageable pageable);
}
