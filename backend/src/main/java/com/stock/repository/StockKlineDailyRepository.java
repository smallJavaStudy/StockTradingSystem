package com.stock.repository;

import com.stock.entity.StockKlineDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockKlineDailyRepository extends JpaRepository<StockKlineDaily, Long> {
    List<StockKlineDaily> findByCodeOrderByTradeDateDesc(String code);
    List<StockKlineDaily> findTop2ByCodeOrderByTradeDateDesc(String code);
    Optional<StockKlineDaily> findTop1ByCodeOrderByTradeDateDesc(String code);
    List<StockKlineDaily> findTop60ByCodeOrderByTradeDateDesc(String code);
    List<StockKlineDaily> findTop120ByCodeOrderByTradeDateDesc(String code);
    Optional<StockKlineDaily> findByCodeAndTradeDate(String code, LocalDate tradeDate);
    long countByCode(String code);
    void deleteByCode(String code);
}
