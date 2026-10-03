package com.stock.repository;

import com.stock.entity.StockNorthFlow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockNorthFlowRepository extends JpaRepository<StockNorthFlow, Long> {

    /** 最近 N 个交易日（倒序），N 由 Pageable 控制 */
    List<StockNorthFlow> findByOrderByTradeDateDesc(Pageable pageable);

    Optional<StockNorthFlow> findByTradeDate(LocalDate tradeDate);
}
