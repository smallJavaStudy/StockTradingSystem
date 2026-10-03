package com.stock.repository;

import com.stock.entity.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    List<PriceAlert> findByCodeOrderByCreatedAtDesc(String code);

    /** AlertScanService 扫描目标：已启用且尚未触发 */
    List<PriceAlert> findByEnabledTrueAndTriggeredFalse();
}
