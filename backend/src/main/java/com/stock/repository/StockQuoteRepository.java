package com.stock.repository;

import com.stock.entity.StockQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface StockQuoteRepository extends JpaRepository<StockQuote, Long> {
    Optional<StockQuote> findTopByCodeOrderByUpdateTimeDesc(String code);
    void deleteByCode(String code);
}
