package com.stock.repository;

import com.stock.entity.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {
    List<AnalysisReport> findByStockCodeAndParadigmOrderByCreatedAtDesc(String stockCode, String paradigm);
    List<AnalysisReport> findByStockCodeOrderByCreatedAtDesc(String stockCode);
}
