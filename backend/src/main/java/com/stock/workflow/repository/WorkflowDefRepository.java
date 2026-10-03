package com.stock.workflow.repository;

import com.stock.workflow.entity.WorkflowDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowDefRepository extends JpaRepository<WorkflowDef, Long> {
    Optional<WorkflowDef> findByName(String name);
}
