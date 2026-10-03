package com.stock.repository;

import com.stock.entity.StockBlockTrade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockBlockTradeRepository extends JpaRepository<StockBlockTrade, Long> {

    List<StockBlockTrade> findByTradeDateOrderByAmountDesc(LocalDate tradeDate);

    List<StockBlockTrade> findByCodeOrderByTradeDateDesc(String code);

    @Query("select max(b.tradeDate) from StockBlockTrade b")
    Optional<LocalDate> findMaxTradeDate();

    @Query("select distinct b.tradeDate from StockBlockTrade b order by b.tradeDate desc")
    List<LocalDate> findDistinctTradeDates(Pageable pageable);
}
