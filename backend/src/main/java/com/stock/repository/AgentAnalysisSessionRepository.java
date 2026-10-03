package com.stock.repository;

import com.stock.entity.AgentAnalysisSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentAnalysisSessionRepository extends JpaRepository<AgentAnalysisSession, Long> {
    Optional<AgentAnalysisSession> findBySessionId(String sessionId);
}
