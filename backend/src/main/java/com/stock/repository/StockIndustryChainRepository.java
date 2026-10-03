package com.stock.repository;

import com.stock.entity.StockIndustryChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface StockIndustryChainRepository extends JpaRepository<StockIndustryChain, Long> {
    Optional<StockIndustryChain> findByStockId(Long stockId);
    void deleteByStockId(Long stockId);
}
