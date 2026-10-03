package com.stock.workflow.repository;

import com.stock.workflow.entity.WorkflowRunLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRunLogRepository extends JpaRepository<WorkflowRunLog, Long> {
    List<WorkflowRunLog> findByProcessInstanceIdOrderByStartedAtAsc(String processInstanceId);
}
