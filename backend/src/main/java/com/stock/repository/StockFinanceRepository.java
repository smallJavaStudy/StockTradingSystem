package com.stock.repository;

import com.stock.entity.StockFinance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockFinanceRepository extends JpaRepository<StockFinance, Long> {
    List<StockFinance> findByCodeOrderByReportDateDesc(String code);
    List<StockFinance> findTop4ByCodeOrderByReportDateDesc(String code);
    Optional<StockFinance> findByCodeAndReportDate(String code, LocalDate reportDate);
    long countByCode(String code);
}
