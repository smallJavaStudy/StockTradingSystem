package com.stock.workflow.repository;

import com.stock.workflow.entity.AgentDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentDefRepository extends JpaRepository<AgentDef, Long> {
    Optional<AgentDef> findByName(String name);
}
