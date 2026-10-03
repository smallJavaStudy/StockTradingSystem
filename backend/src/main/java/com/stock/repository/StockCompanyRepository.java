package com.stock.repository;

import com.stock.entity.StockCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface StockCompanyRepository extends JpaRepository<StockCompany, Long> {
    Optional<StockCompany> findByStockId(Long stockId);
}
