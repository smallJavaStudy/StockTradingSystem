package com.stock.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.agent.*;
import com.stock.entity.AgentAnalysisResult;
import com.stock.entity.AgentAnalysisSession;
import com.stock.entity.StockBasic;
import com.stock.repository.AgentAnalysisResultRepository;
import com.stock.repository.AgentAnalysisSessionRepository;
import com.stock.repository.StockBasicRepository;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AgentOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DELTA_FLUSH_MS = 60; // coalesce deltas within this window

    private final AgentFactory agentFactory;
    private final DataContextBuilder dataContextBuilder;
    private final DataEnricher dataEnricher;
    private final ProfileClient profileClient;
    private final StockBasicRepository stockBasicRepo;
    private final AgentAnalysisSessionRepository sessionRepo;
    private final AgentAnalysisResultRepository resultRepo;
    private final Executor executor;
    private final ScheduledExecutorService flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "delta-flush");
        t.setDaemon(true);
        return t;
    });

    // Session state tracking
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public AgentOrchestrationService(AgentFactory agentFactory,
                                      DataContextBuilder dataContextBuilder,
                                      DataEnricher dataEnricher,
                                      ProfileClient profileClient,
                                      StockBasicRepository stockBasicRepo,
                                      AgentAnalysisSessionRepository sessionRepo,
                                      AgentAnalysisResultRepository resultRepo,
                                      @Qualifier("agentExecutor") Executor executor) {
        this.agentFactory = agentFactory;
        this.dataContextBuilder = dataContextBuilder;
        this.dataEnricher = dataEnricher;
        this.profileClient = profileClient;
        this.stockBasicRepo = stockBasicRepo;
        this.sessionRepo = sessionRepo;
        this.resultRepo = resultRepo;
        this.executor = executor;
    }

    // ═══════════════════════════════════════════════════════════
    //  股票名称解析
    // ═══════════════════════════════════════════════════════════

    public List<StockCandidate> resolveStockName(String query) {
        HarnessAgent agent = agentFactory.createResolverAgent();
        try {
            Msg result = agent.call(List.of(new UserMessage("请解析以下股票名称：\"" + query + "\"")))
                    .block();
            if (result == null || result.getTextContent() == null) {
                return List.of();
            }
            String json = cleanJson(result.getTextContent());
            JsonNode arr = MAPPER.readTree(json);
            List<StockCandidate> candidates = new ArrayList<>();
            for (JsonNode node : arr) {
                candidates.add(new StockCandidate(
                        node.get("code").asText(),
                        node.get("name").asText(),
                        node.get("market").asText(),
                        node.has("industry") ? node.get("industry").asText() : ""
                ));
            }
            return candidates;
        } catch (Exception e) {
            log.error("Failed to resolve stock name: {}", query, e);
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  启动分析
    // ═══════════════════════════════════════════════════════════

    public String startAnalysis(String stockCode, List<String> directionKeys, String modelChoiceKey) {
        StockBasic stock = stockBasicRepo.findByCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("Stock not found: " + stockCode));

        ModelChoice modelChoice = ModelChoice.fromKey(modelChoiceKey);
        String sessionId = UUID.randomUUID().toString();

        // Create and save session
        AgentAnalysisSession session = new AgentAnalysisSession();
        session.setSessionId(sessionId);
        session.setStockCode(stockCode);
        session.setStockName(stock.getName());
        session.setModelChoice(modelChoice.getDisplayName());
        session.setStatus("RUNNING");
        sessionRepo.save(session);

        // Build data context once
        String dataContext = dataContextBuilder.buildDataContext(stock);

        // Initialize session state
        SessionState state = new SessionState(sessionId, session, directionKeys.size());
        sessions.put(sessionId, state);

        // 异步获取用户画像（不阻塞主流程）
        CompletableFuture.runAsync(() -> {
            String ctx = profileClient.fetchPromptContext("user_small");
            if (ctx != null && !ctx.isBlank()) {
                state.profileContext = ctx;
                log.info("用户画像已注入分析智能体 ({} 字符)", ctx.length());
            }
        }, executor);

        // Resolve directions
        Set<String> keys = new HashSet<>(directionKeys);
        List<AnalysisDirection> phase1 = new ArrayList<>();
        boolean hasComprehensive = false;

        for (AnalysisDirection d : AnalysisDirection.analysisDirections()) {
            if (keys.contains(d.getDirectionKey())) {
                if (d == AnalysisDirection.COMPREHENSIVE_ADVICE) {
                    hasComprehensive = true;
                } else {
                    phase1.add(d);
                }
            }
        }

        // Launch phase 1 tasks in parallel
        long startTime = System.currentTimeMillis();
        for (AnalysisDirection direction : phase1) {
            CompletableFuture.runAsync(() ->
                    executeAnalysisStream(sessionId, direction, modelChoice, dataContext, startTime),
                    executor);
        }

        // If no comprehensive advice requested, or no phase 1 tasks, complete immediately
        if (!hasComprehensive) {
            state.phaseTwoDone = true;
            checkAndCompleteSession(sessionId);
        } else if (phase1.isEmpty()) {
            // Only comprehensive advice, run it directly
            CompletableFuture.runAsync(() ->
                    executeAnalysisStream(sessionId, AnalysisDirection.COMPREHENSIVE_ADVICE,
                            modelChoice, dataContext, startTime), executor);
        }
        // Otherwise, phase 2 is triggered when all phase 1 completes in onPhaseOneComplete

        return sessionId;
    }

    // ═══════════════════════════════════════════════════════════
    //  SSE 订阅
    // ═══════════════════════════════════════════════════════════

    public SseEmitter subscribeToSession(String sessionId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5 min timeout

        SessionState state = sessions.get(sessionId);
        if (state == null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"error\":\"Session not found: " + sessionId + "\"}"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // Replay historical events first
        synchronized (state.eventHistory) {
            for (AnalysisStreamEvent event : state.eventHistory) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("analysis-event")
                            .data(MAPPER.writeValueAsString(event)));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    return emitter;
                }
            }
        }

        // Register for future events
        state.emitters.add(emitter);

        emitter.onCompletion(() -> state.emitters.remove(emitter));
        emitter.onTimeout(() -> state.emitters.remove(emitter));
        emitter.onError(e -> state.emitters.remove(emitter));

        // If session is already complete, send complete event
        if (state.isComplete()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("session-complete")
                        .data("{\"sessionId\":\"" + sessionId + "\",\"status\":\"" + state.session.getStatus() + "\"}"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }

        return emitter;
    }

    // ═══════════════════════════════════════════════════════════
    //  轮询结果（降级方案）
    // ═══════════════════════════════════════════════════════════

    public AgentAnalysisSession getSession(String sessionId) {
        return sessionRepo.findBySessionId(sessionId).orElse(null);
    }

    public List<AgentAnalysisResult> getSessionResults(String sessionId) {
        return resultRepo.findBySessionIdOrderByDirectionAsc(sessionId);
    }

    public List<AgentAnalysisResult> getStockHistory(String stockCode) {
        // Get session IDs for this stock code, then find results
        // Simplified: query directly by stock code in a broader query
        // For now, we'll just query recent sessions
        return resultRepo.findAll().stream()
                .filter(r -> {
                    Optional<AgentAnalysisSession> s = sessionRepo.findById(
                            sessions.values().stream()
                                    .filter(st -> st.sessionId.equals(r.getSessionId()))
                                    .findFirst()
                                    .map(st -> st.session.getId())
                                    .orElseGet(() -> sessionRepo.findBySessionId(r.getSessionId())
                                            .map(AgentAnalysisSession::getId).orElse(null)));
                    return false; // simplified, will implement properly later
                })
                .toList();
    }

    // ═══════════════════════════════════════════════════════════
    //  核心分析执行（异步，SSE 驱动）
    // ═══════════════════════════════════════════════════════════

    private void executeAnalysisStream(String sessionId, AnalysisDirection direction,
                                        ModelChoice modelChoice, String dataContext, long startTime) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;

        String dirKey = direction.getDirectionKey();

        // Enrich data context with external LLM if this direction has an enrichment spec
        AnalysisDirection.EnrichmentSpec enrichSpec = direction.getEnrichmentSpec();
        final String enrichedContext;
        try {
            enrichedContext = dataEnricher.enrich(
                    state.session.getStockCode(), state.session.getStockName(),
                    dataContext, dirKey, enrichSpec);
        } catch (RuntimeException e) {
            // 数据获取失败：立即中止该方向的分析
            log.error("Data enrichment failed for {}: {}", dirKey, e.getMessage());
            String errMsg = "数据获取失败: " + e.getMessage();
            emitEvent(state, new AnalysisStreamEvent(dirKey,
                    "AGENT_ERROR", errMsg, System.currentTimeMillis(), Map.of()));
            saveResult(sessionId, dirKey, direction.getDisplayName(),
                    modelChoice.getDisplayName(), null,
                    "", 0, 0, System.currentTimeMillis() - startTime,
                    "FAILED", errMsg);
            if (direction != AnalysisDirection.COMPREHENSIVE_ADVICE) {
                state.phaseOneResults.put(dirKey, "{\"error\":\"" + escapeJson(errMsg) + "\"}");
                onAnalysisComplete(sessionId);
            } else {
                state.phaseTwoDone = true;
                checkAndCompleteSession(sessionId);
            }
            return;
        }

        try {
            HarnessAgent agent = agentFactory.createAnalysisAgent(direction, modelChoice,
                    state.session.getStockCode(), enrichedContext, state.profileContext);

            // Send start event
            emitEvent(state, new AnalysisStreamEvent(dirKey, "AGENT_START",
                    "开始" + direction.getDisplayName(), System.currentTimeMillis(), Map.of()));

            // Stream events with delta coalescing
            AtomicLong inputTokens = new AtomicLong(0);
            AtomicLong outputTokens = new AtomicLong(0);
            StringBuilder fullText = new StringBuilder();
            StringBuilder thinkingTrace = new StringBuilder();

            // Delta buffer — coalesce small deltas before emitting (60ms window)
            StringBuilder thinkingBuf = new StringBuilder();
            StringBuilder textBuf = new StringBuilder();
            ScheduledFuture<?>[] flushTask = new ScheduledFuture<?>[1];
            Runnable doFlush = () -> {
                String t, txt;
                synchronized (thinkingBuf) {
                    t = thinkingBuf.isEmpty() ? null : thinkingBuf.toString();
                    txt = textBuf.isEmpty() ? null : textBuf.toString();
                    thinkingBuf.setLength(0);
                    textBuf.setLength(0);
                    flushTask[0] = null;
                }
                if (t != null) {
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "THINKING_DELTA", t, System.currentTimeMillis(), Map.of()));
                }
                if (txt != null) {
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "TEXT_DELTA", txt, System.currentTimeMillis(), Map.of()));
                }
            };

            Disposable subscription = agent.streamEvents(
                    List.of(new UserMessage("请对股票 " + state.session.getStockCode() +
                            "(" + state.session.getStockName() + ") 进行分析。" +
                            "以下是已采集的数据：\n\n" + enrichedContext)))
                    .doOnNext(event -> {
                        AgentEventType type = event.getType();
                        switch (type) {
                            case THINKING_BLOCK_DELTA -> {
                                if (event instanceof ThinkingBlockDeltaEvent t) {
                                    String delta = t.getDelta();
                                    thinkingTrace.append(delta);
                                    synchronized (thinkingBuf) {
                                        thinkingBuf.append(delta);
                                        if (flushTask[0] != null) flushTask[0].cancel(false);
                                        flushTask[0] = flushScheduler.schedule(doFlush, DELTA_FLUSH_MS, TimeUnit.MILLISECONDS);
                                    }
                                }
                            }
                            case TEXT_BLOCK_DELTA -> {
                                if (event instanceof TextBlockDeltaEvent t) {
                                    String delta = t.getDelta();
                                    fullText.append(delta);
                                    synchronized (thinkingBuf) {
                                        textBuf.append(delta);
                                        if (flushTask[0] != null) flushTask[0].cancel(false);
                                        flushTask[0] = flushScheduler.schedule(doFlush, DELTA_FLUSH_MS, TimeUnit.MILLISECONDS);
                                    }
                                }
                            }
                            case TOOL_CALL_START -> {
                                // Flush pending deltas before tool event
                                synchronized (thinkingBuf) {
                                    if (flushTask[0] != null) { flushTask[0].cancel(false); flushTask[0] = null; }
                                    doFlush.run();
                                }
                                if (event instanceof ToolCallStartEvent t) {
                                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                                            "TOOL_CALL_START", t.getToolCallName(),
                                            System.currentTimeMillis(),
                                            Map.of("toolCallId", t.getToolCallId())));
                                }
                            }
                            case TOOL_CALL_DELTA -> {
                                if (event instanceof ToolCallDeltaEvent t) {
                                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                                            "TOOL_CALL_ARGS", t.getDelta(),
                                            System.currentTimeMillis(),
                                            Map.of("toolCallId", t.getToolCallId())));
                                }
                            }
                            case TOOL_RESULT_TEXT_DELTA -> {
                                if (event instanceof ToolResultTextDeltaEvent t) {
                                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                                            "TOOL_RESULT_DELTA", t.getDelta(),
                                            System.currentTimeMillis(),
                                            Map.of("toolCallId", t.getToolCallId())));
                                }
                            }
                            case TOOL_RESULT_END -> {
                                if (event instanceof ToolResultEndEvent t) {
                                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                                            "TOOL_RESULT_END", "",
                                            System.currentTimeMillis(),
                                            Map.of("toolCallId", t.getToolCallId(),
                                                    "state", t.getState().name())));
                                }
                            }
                            case MODEL_CALL_END -> {
                                if (event instanceof ModelCallEndEvent t) {
                                    ChatUsage usage = t.getUsage();
                                    if (usage != null) {
                                        inputTokens.set(usage.getInputTokens());
                                        outputTokens.set(usage.getOutputTokens());
                                    }
                                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                                            "MODEL_CALL_END", "",
                                            System.currentTimeMillis(),
                                            Map.of("inputTokens", inputTokens.get(),
                                                    "outputTokens", outputTokens.get())));
                                }
                            }
                            case AGENT_START -> { /* silent */ }
                            case AGENT_END -> { /* handled by onComplete */ }
                            default -> { /* ignore other events */ }
                        }
                    })
                    .doOnComplete(() -> {
                        // Flush remaining buffered deltas
                        synchronized (thinkingBuf) {
                            if (flushTask[0] != null) { flushTask[0].cancel(false); flushTask[0] = null; }
                            doFlush.run();
                        }
                        long elapsed = System.currentTimeMillis() - startTime;
                        String finalText = fullText.toString();
                        String cleanResult = cleanJson(finalText);

                        // Ensure valid JSON
                        if (!cleanResult.startsWith("{") && cleanResult.contains("{")) {
                            cleanResult = cleanResult.substring(cleanResult.indexOf("{"));
                            if (cleanResult.endsWith("```")) {
                                cleanResult = cleanResult.substring(0, cleanResult.lastIndexOf("```"));
                            }
                        }

                        emitEvent(state, new AnalysisStreamEvent(dirKey,
                                "AGENT_COMPLETE", cleanResult, System.currentTimeMillis(),
                                Map.of("elapsedMs", elapsed)));

                        // Save result
                        saveResult(sessionId, dirKey, direction.getDisplayName(),
                                agent.getModel().getModelName(), cleanResult,
                                thinkingTrace.toString(), inputTokens.get(),
                                outputTokens.get(), elapsed, "SUCCESS", null);

                        // Track phase 1 completion
                        if (direction != AnalysisDirection.COMPREHENSIVE_ADVICE) {
                            state.phaseOneResults.put(dirKey, cleanResult);
                            onAnalysisComplete(sessionId);
                        } else {
                            state.phaseTwoDone = true;
                            checkAndCompleteSession(sessionId);
                        }
                    })
                    .doOnError(error -> {
                        // Flush remaining buffered deltas
                        synchronized (thinkingBuf) {
                            if (flushTask[0] != null) { flushTask[0].cancel(false); flushTask[0] = null; }
                            doFlush.run();
                        }
                        log.error("Analysis failed for {}: {}", dirKey, error.getMessage());
                        long elapsed = System.currentTimeMillis() - startTime;

                        emitEvent(state, new AnalysisStreamEvent(dirKey,
                                "AGENT_ERROR", error.getMessage(), System.currentTimeMillis(),
                                Map.of("elapsedMs", elapsed)));

                        saveResult(sessionId, dirKey, direction.getDisplayName(),
                                agent.getModel().getModelName(), null,
                                thinkingTrace.toString(), inputTokens.get(),
                                outputTokens.get(), elapsed, "FAILED", error.getMessage());

                        if (direction != AnalysisDirection.COMPREHENSIVE_ADVICE) {
                            state.phaseOneResults.put(dirKey, "{\"error\":\"" +
                                    escapeJson(error.getMessage()) + "\"}");
                            onAnalysisComplete(sessionId);
                        } else {
                            state.phaseTwoDone = true;
                            checkAndCompleteSession(sessionId);
                        }
                    })
                    .subscribe();

            // Store disposable for cleanup
            state.disposables.add(subscription);

        } catch (Exception e) {
            log.error("Failed to create agent for {}: {}", dirKey, e.getMessage());
            emitEvent(state, new AnalysisStreamEvent(dirKey,
                    "AGENT_ERROR", e.getMessage(), System.currentTimeMillis(), Map.of()));

            if (direction != AnalysisDirection.COMPREHENSIVE_ADVICE) {
                state.phaseOneResults.put(dirKey, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                onAnalysisComplete(sessionId);
            } else {
                state.phaseTwoDone = true;
                checkAndCompleteSession(sessionId);
            }
        }
    }

    private void onAnalysisComplete(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;

        int completed = state.completedCount.incrementAndGet();

        // Check if all phase 1 is done and comprehensive advice is needed
        if (completed >= state.totalPhaseOne && !state.phaseTwoStarted.getAndSet(true)) {
            // Run comprehensive advice
            StockBasic stock = stockBasicRepo.findByCode(state.session.getStockCode()).orElse(null);
            if (stock != null) {
                String dataContext = dataContextBuilder.buildDataContext(stock);
                String combinedResults = buildCombinedResults(state);

                CompletableFuture.runAsync(() -> {
                    executeComprehensiveAnalysis(sessionId, combinedResults, dataContext);
                }, executor);
            } else {
                state.phaseTwoDone = true;
                checkAndCompleteSession(sessionId);
            }
        }
    }

    private void executeComprehensiveAnalysis(String sessionId, String combinedResults,
                                                String dataContext) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;

        long startTime = System.currentTimeMillis();

        try {
            HarnessAgent agent = agentFactory.createAnalysisAgent(
                    AnalysisDirection.COMPREHENSIVE_ADVICE, ModelChoice.fromKey(
                            state.session.getModelChoice().toLowerCase().contains("deepseek")
                                    ? (state.session.getModelChoice().contains("V4") ? "deepseek-v4" : "deepseek-v5")
                                    : "kimi"),
                    state.session.getStockCode(), dataContext, state.profileContext);

            String prompt = "请基于以下各维度的分析结果，对股票 " + state.session.getStockCode() +
                    "(" + state.session.getStockName() + ") 给出综合投资建议。\n\n" +
                    "各维度分析结果：\n" + combinedResults;

            String dirKey = AnalysisDirection.COMPREHENSIVE_ADVICE.getDirectionKey();
            emitEvent(state, new AnalysisStreamEvent(dirKey, "AGENT_START",
                    "开始综合投资建议", System.currentTimeMillis(), Map.of()));

            StringBuilder fullText = new StringBuilder();
            StringBuilder thinkingTrace = new StringBuilder();
            AtomicLong inputTokens = new AtomicLong(0);
            AtomicLong outputTokens = new AtomicLong(0);

            Disposable subscription = agent.streamEvents(
                    List.of(new UserMessage(prompt)))
                    .doOnNext(event -> handleStreamEvent(state, event, dirKey,
                            fullText, thinkingTrace, inputTokens, outputTokens))
                    .doOnComplete(() -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        String cleanResult = cleanJson(fullText.toString());
                        if (!cleanResult.startsWith("{") && cleanResult.contains("{")) {
                            cleanResult = cleanResult.substring(cleanResult.indexOf("{"));
                        }

                        emitEvent(state, new AnalysisStreamEvent(dirKey,
                                "AGENT_COMPLETE", cleanResult, System.currentTimeMillis(),
                                Map.of("elapsedMs", elapsed)));

                        saveResult(sessionId, dirKey, "综合投资建议",
                                agent.getModel().getModelName(), cleanResult,
                                thinkingTrace.toString(), inputTokens.get(),
                                outputTokens.get(), elapsed, "SUCCESS", null);

                        state.phaseTwoDone = true;
                        checkAndCompleteSession(sessionId);
                    })
                    .doOnError(error -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        emitEvent(state, new AnalysisStreamEvent(dirKey,
                                "AGENT_ERROR", error.getMessage(), System.currentTimeMillis(),
                                Map.of("elapsedMs", elapsed)));

                        saveResult(sessionId, dirKey, "综合投资建议",
                                agent.getModel().getModelName(), null,
                                thinkingTrace.toString(), inputTokens.get(),
                                outputTokens.get(), elapsed, "FAILED", error.getMessage());

                        state.phaseTwoDone = true;
                        checkAndCompleteSession(sessionId);
                    })
                    .subscribe();

            state.disposables.add(subscription);

        } catch (Exception e) {
            log.error("Comprehensive analysis failed: {}", e.getMessage());
            state.phaseTwoDone = true;
            checkAndCompleteSession(sessionId);
        }
    }

    private void handleStreamEvent(SessionState state, AgentEvent event, String dirKey,
                                     StringBuilder fullText, StringBuilder thinkingTrace,
                                     AtomicLong inputTokens, AtomicLong outputTokens) {
        AgentEventType type = event.getType();
        switch (type) {
            case THINKING_BLOCK_DELTA -> {
                if (event instanceof ThinkingBlockDeltaEvent t) {
                    thinkingTrace.append(t.getDelta());
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "THINKING_DELTA", t.getDelta(), System.currentTimeMillis(), Map.of()));
                }
            }
            case TEXT_BLOCK_DELTA -> {
                if (event instanceof TextBlockDeltaEvent t) {
                    fullText.append(t.getDelta());
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "TEXT_DELTA", t.getDelta(), System.currentTimeMillis(), Map.of()));
                }
            }
            case TOOL_CALL_START -> {
                if (event instanceof ToolCallStartEvent t) {
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "TOOL_CALL_START", t.getToolCallName(),
                            System.currentTimeMillis(), Map.of("toolCallId", t.getToolCallId())));
                }
            }
            case TOOL_CALL_DELTA -> {
                if (event instanceof ToolCallDeltaEvent t) {
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "TOOL_CALL_ARGS", t.getDelta(),
                            System.currentTimeMillis(), Map.of("toolCallId", t.getToolCallId())));
                }
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                if (event instanceof ToolResultTextDeltaEvent t) {
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "TOOL_RESULT_DELTA", t.getDelta(),
                            System.currentTimeMillis(), Map.of("toolCallId", t.getToolCallId())));
                }
            }
            case TOOL_RESULT_END -> {
                if (event instanceof ToolResultEndEvent t) {
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "TOOL_RESULT_END", "",
                            System.currentTimeMillis(),
                            Map.of("toolCallId", t.getToolCallId(), "state", t.getState().name())));
                }
            }
            case MODEL_CALL_END -> {
                if (event instanceof ModelCallEndEvent t) {
                    ChatUsage usage = t.getUsage();
                    if (usage != null) {
                        inputTokens.set(usage.getInputTokens());
                        outputTokens.set(usage.getOutputTokens());
                    }
                    emitEvent(state, new AnalysisStreamEvent(dirKey,
                            "MODEL_CALL_END", "",
                            System.currentTimeMillis(),
                            Map.of("inputTokens", inputTokens.get(),
                                    "outputTokens", outputTokens.get())));
                }
            }
            default -> { /* ignore */ }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════

    private void emitEvent(SessionState state, AnalysisStreamEvent event) {
        synchronized (state.eventHistory) {
            state.eventHistory.add(event);
        }

        String json;
        try {
            json = MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event", e);
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : state.emitters) {
            try {
                emitter.send(SseEmitter.event().name("analysis-event").data(json));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        state.emitters.removeAll(deadEmitters);
    }

    private void checkAndCompleteSession(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;

        if (state.isComplete()) {
            // Determine status
            String status;
            long failedCount = resultRepo.findBySessionId(sessionId).stream()
                    .filter(r -> "FAILED".equals(r.getStatus())).count();
            long totalCount = resultRepo.findBySessionId(sessionId).size();

            if (failedCount == 0) {
                status = "COMPLETED";
            } else if (failedCount == totalCount) {
                status = "FAILED";
            } else {
                status = "PARTIAL_FAILED";
            }

            long elapsed = System.currentTimeMillis() - state.session.getCreatedAt().getNano() / 1_000_000;
            // Approximate elapsed
            state.session.setStatus(status);
            state.session.setTotalElapsedMs(elapsed);
            sessionRepo.save(state.session);

            // Send completion to all emitters
            for (SseEmitter emitter : state.emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("session-complete")
                            .data("{\"sessionId\":\"" + sessionId + "\",\"status\":\"" + status + "\"}"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }
            state.emitters.clear();

            // Clean up after a delay
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    cleanup(sessionId);
                }
            }, 60_000);
        }
    }

    private void saveResult(String sessionId, String direction, String agentName,
                             String modelName, String contentJson, String thinkingTrace,
                             long inputTokens, long outputTokens, long elapsedMs,
                             String status, String errorMessage) {
        try {
            AgentAnalysisResult result = new AgentAnalysisResult();
            result.setSessionId(sessionId);
            result.setDirection(direction);
            result.setAgentName(agentName);
            result.setModelName(modelName);
            result.setContentJson(contentJson);
            result.setThinkingTrace(thinkingTrace);
            result.setInputTokens(inputTokens);
            result.setOutputTokens(outputTokens);
            result.setElapsedMs(elapsedMs);
            result.setStatus(status);
            result.setErrorMessage(errorMessage);
            resultRepo.save(result);
        } catch (Exception e) {
            log.error("Failed to save result for {}: {}", direction, e.getMessage());
        }
    }

    private String buildCombinedResults(SessionState state) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : state.phaseOneResults.entrySet()) {
            sb.append("【").append(entry.getKey()).append("】\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        return sb.toString();
    }

    private static String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        return s.trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private void cleanup(String sessionId) {
        SessionState state = sessions.remove(sessionId);
        if (state != null) {
            for (Disposable d : state.disposables) {
                if (!d.isDisposed()) d.dispose();
            }
            for (SseEmitter emitter : state.emitters) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(state -> {
            for (Disposable d : state.disposables) {
                if (!d.isDisposed()) d.dispose();
            }
            for (SseEmitter emitter : state.emitters) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });
        sessions.clear();
        flushScheduler.shutdownNow();
    }

    // ═══════════════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════════════

    private static class SessionState {
        final String sessionId;
        final AgentAnalysisSession session;
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final List<AnalysisStreamEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger completedCount = new AtomicInteger(0);
        final int totalPhaseOne;
        final ConcurrentHashMap<String, String> phaseOneResults = new ConcurrentHashMap<>();
        final AtomicBoolean phaseTwoStarted = new AtomicBoolean(false);
        volatile boolean phaseTwoDone = false;
        final List<Disposable> disposables = new CopyOnWriteArrayList<>();
        volatile String profileContext = null;  // 用户画像适配文本

        SessionState(String sessionId, AgentAnalysisSession session, int phaseOneCount) {
            this.sessionId = sessionId;
            this.session = session;
            this.totalPhaseOne = phaseOneCount;
            // If no phase 1 tasks, phase two can start immediately
            if (phaseOneCount == 0) {
                this.phaseTwoStarted.set(true);
                this.phaseTwoDone = true;
            }
        }

        boolean isComplete() {
            return completedCount.get() >= totalPhaseOne && phaseTwoDone;
        }
    }
}
