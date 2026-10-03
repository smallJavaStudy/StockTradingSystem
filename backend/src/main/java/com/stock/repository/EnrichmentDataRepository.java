package com.stock.repository;

import com.stock.entity.EnrichmentData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EnrichmentDataRepository extends JpaRepository<EnrichmentData, Long> {

    /** 查找某股票某方向的最新一条缓存数据 */
    Optional<EnrichmentData> findTopByStockCodeAndDirectionKeyOrderByCreatedAtDesc(
            String stockCode, String directionKey);

    /** 删除某股票某方向的所有缓存（用于手动刷新） */
    void deleteByStockCodeAndDirectionKey(String stockCode, String directionKey);
}
