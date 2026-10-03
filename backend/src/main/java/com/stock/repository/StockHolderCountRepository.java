package com.stock.repository;

import com.stock.entity.StockHolderCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockHolderCountRepository extends JpaRepository<StockHolderCount, Long> {

    /** 户数趋势（时间升序，便于前端直接画折线） */
    List<StockHolderCount> findByCodeOrderByStatDateAsc(String code);
}
