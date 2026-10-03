package com.stock.repository;

import com.stock.entity.StockProductBreakdown;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StockProductBreakdownRepository extends JpaRepository<StockProductBreakdown, Long> {
    List<StockProductBreakdown> findByStockIdOrderByReportDateDesc(Long stockId);
    void deleteByStockId(Long stockId);
}
