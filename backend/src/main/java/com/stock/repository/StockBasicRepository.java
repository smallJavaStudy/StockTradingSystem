package com.stock.repository;

import com.stock.entity.StockBasic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface StockBasicRepository extends JpaRepository<StockBasic, Long> {
    Optional<StockBasic> findByCode(String code);
    boolean existsByCode(String code);
}
