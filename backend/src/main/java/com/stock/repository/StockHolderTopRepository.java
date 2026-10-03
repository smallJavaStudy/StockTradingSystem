package com.stock.repository;

import com.stock.entity.StockHolderTop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StockHolderTopRepository extends JpaRepository<StockHolderTop, Long> {

    List<StockHolderTop> findByCodeAndHolderTypeOrderByReportDateDescHolderRankAsc(String code, String holderType);

    List<StockHolderTop> findByCodeAndHolderTypeAndReportDateInOrderByReportDateDescHolderRankAsc(
            String code, String holderType, List<LocalDate> reportDates);

    @Query("select distinct h.reportDate from StockHolderTop h where h.code = ?1 and h.holderType = ?2 order by h.reportDate desc")
    List<LocalDate> findDistinctReportDates(String code, String holderType);
}
