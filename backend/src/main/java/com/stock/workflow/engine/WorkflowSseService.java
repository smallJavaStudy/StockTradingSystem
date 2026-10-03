package com.stock.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * 工作流 SSE 推送服务（参照 AgentOrchestrationService 的会话 SSE 管理模式）：
 * ConcurrentHashMap&lt;processInstanceId, 状态&gt; + 事件历史回放 + emitter 生命周期钩子。
 * 事件名统一 "workflow-event"，payload 为 {@link WorkflowEvent} JSON。
 */
@Service
public class WorkflowSseService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** SSE 超时：30 分钟 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    /** 流程终止后状态保留时长（供晚到订阅者回放历史），之后清理 */
    private static final long STATE_RETENTION_MS = 10 * 60 * 1000L;

    private final ConcurrentHashMap<String, ProcessSseState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "workflow-sse-cleaner");
                t.setDaemon(true);
                return t;
            });

    /**
     * 订阅流程实例事件流：先回放历史事件，再注册接收后续事件。
     * 若流程已终止，回放完历史后立即 complete。
     */
    public SseEmitter subscribe(String processInstanceId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ProcessSseState state = states.computeIfAbsent(processInstanceId, k -> new ProcessSseState());

        // 回放历史
        synchronized (state.eventHistory) {
            for (WorkflowEvent event : state.eventHistory) {
                if (!sendEvent(emitter, event)) {
                    return emitter; // 发送失败已 completeWithError
                }
            }
        }

        if (state.completed) {
            emitter.complete();
            return emitter;
        }

        state.emitters.add(emitter);
        emitter.onCompletion(() -> state.emitters.remove(emitter));
        emitter.onTimeout(() -> state.emitters.remove(emitter));
        emitter.onError(e -> state.emitters.remove(emitter));
        return emitter;
    }

    /**
     * 推送事件：写入历史 + 广播给所有订阅者。
     * 流程级终止事件推送完成后标记完成并 complete 所有 emitter。
     */
    public void push(String processInstanceId, WorkflowEvent event) {
        ProcessSseState state = states.computeIfAbsent(processInstanceId, k -> new ProcessSseState());
        if (state.completed) {
            return; // 已终止的流程不再接收事件
        }
        synchronized (state.eventHistory) {
            state.eventHistory.add(event);
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : state.emitters) {
            if (!sendEvent(emitter, event)) {
                dead.add(emitter);
            }
        }
        state.emitters.removeAll(dead);

        if (event.isTerminal()) {
            state.completed = true;
            for (SseEmitter emitter : state.emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            state.emitters.clear();
            cleaner.schedule(() -> states.remove(processInstanceId),
                    STATE_RETENTION_MS, TimeUnit.MILLISECONDS);
        }
    }

    private boolean sendEvent(SseEmitter emitter, WorkflowEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name("workflow-event")
                    .data(MAPPER.writeValueAsString(event)));
            return true;
        } catch (IOException | IllegalStateException e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
            return false;
        } catch (Exception e) {
            log.error("SSE 事件序列化/发送失败: {}", e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        states.values().forEach(state -> {
            for (SseEmitter emitter : state.emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
        states.clear();
        cleaner.shutdownNow();
    }

    private static class ProcessSseState {
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final List<WorkflowEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());
        volatile boolean completed = false;
    }
}
