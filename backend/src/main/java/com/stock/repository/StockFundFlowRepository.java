package com.stock.repository;

import com.stock.entity.StockFundFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockFundFlowRepository extends JpaRepository<StockFundFlow, Long> {
    List<StockFundFlow> findTop20ByCodeOrderByTradeDateDesc(String code);
    List<StockFundFlow> findTop60ByCodeOrderByTradeDateDesc(String code);
    Optional<StockFundFlow> findByCodeAndTradeDate(String code, LocalDate tradeDate);
    long countByCode(String code);
    void deleteByCode(String code);
}
