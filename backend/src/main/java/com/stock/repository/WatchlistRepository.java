package com.stock.repository;

import com.stock.entity.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {
    Optional<Watchlist> findByStockCode(String stockCode);
    boolean existsByStockCode(String stockCode);
    List<Watchlist> findAllByOrderByGroupNameAscCreatedAtDesc();
}
