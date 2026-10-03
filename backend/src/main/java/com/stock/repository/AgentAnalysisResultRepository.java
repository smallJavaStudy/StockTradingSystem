package com.stock.repository;

import com.stock.entity.AgentAnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentAnalysisResultRepository extends JpaRepository<AgentAnalysisResult, Long> {
    List<AgentAnalysisResult> findBySessionId(String sessionId);
    List<AgentAnalysisResult> findBySessionIdOrderByDirectionAsc(String sessionId);
}
