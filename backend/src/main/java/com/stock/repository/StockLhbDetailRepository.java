package com.stock.repository;

import com.stock.entity.StockLhbDetail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockLhbDetailRepository extends JpaRepository<StockLhbDetail, Long> {

    List<StockLhbDetail> findByTradeDateOrderByNetAmountDesc(LocalDate tradeDate);

    List<StockLhbDetail> findByCodeOrderByTradeDateDesc(String code);

    @Query("select max(l.tradeDate) from StockLhbDetail l")
    Optional<LocalDate> findMaxTradeDate();

    @Query("select distinct l.tradeDate from StockLhbDetail l order by l.tradeDate desc")
    List<LocalDate> findDistinctTradeDates(Pageable pageable);
}
