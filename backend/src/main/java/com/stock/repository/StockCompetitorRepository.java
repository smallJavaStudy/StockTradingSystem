package com.stock.repository;

import com.stock.entity.StockCompetitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StockCompetitorRepository extends JpaRepository<StockCompetitor, Long> {
    List<StockCompetitor> findByStockId(Long stockId);
    void deleteByStockId(Long stockId);
}
