package com.stock.controller;

import com.stock.agent.StockCandidate;
import com.stock.entity.AgentAnalysisResult;
import com.stock.entity.AgentAnalysisSession;
import com.stock.service.AgentOrchestrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis-agent")
public class AnalysisAgentController {

    @Autowired
    private AgentOrchestrationService orchestrationService;

    @PostMapping("/resolve")
    public List<StockCandidate> resolveStockName(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        return orchestrationService.resolveStockName(query);
    }

    @PostMapping("/analyze")
    public Map<String, String> startAnalysis(@RequestBody Map<String, Object> body) {
        String stockCode = (String) body.get("stockCode");
        @SuppressWarnings("unchecked")
        List<String> directions = (List<String>) body.get("directions");
        String modelChoice = (String) body.get("modelChoice");

        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("stockCode is required");
        }
        if (directions == null || directions.isEmpty()) {
            throw new IllegalArgumentException("directions is required");
        }
        if (modelChoice == null || modelChoice.isBlank()) {
            throw new IllegalArgumentException("modelChoice is required");
        }

        String sessionId = orchestrationService.startAnalysis(stockCode, directions, modelChoice);
        return Map.of("sessionId", sessionId);
    }

    @GetMapping(value = "/stream/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnalysis(@PathVariable String sessionId) {
        return orchestrationService.subscribeToSession(sessionId);
    }

    @GetMapping("/session/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        AgentAnalysisSession session = orchestrationService.getSession(sessionId);
        List<AgentAnalysisResult> results = orchestrationService.getSessionResults(sessionId);
        return Map.of("session", session, "results", results);
    }

    @GetMapping("/stock/{code}/history")
    public List<AgentAnalysisResult> getStockHistory(@PathVariable String code) {
        return orchestrationService.getStockHistory(code);
    }
}
