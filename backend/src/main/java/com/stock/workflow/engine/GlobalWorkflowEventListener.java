package com.stock.workflow.engine;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableExceptionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 全局 Flowable 引擎事件监听器：将流程级事件转发为工作流 SSE 事件。
 * <ul>
 *   <li>PROCESS_COMPLETED → SSE PROCESS_COMPLETED</li>
 *   <li>PROCESS_CANCELLED → SSE PROCESS_CANCELLED</li>
 *   <li>JOB_EXECUTION_FAILURE（异步节点 Job 执行失败）→ SSE PROCESS_FAILED</li>
 * </ul>
 * processInstanceId 从 {@link FlowableEngineEvent#getProcessInstanceId()} 获取
 * （流程级/执行级引擎事件均携带该字段）。
 * 通过 {@code WorkflowEngineConfig} 的 EngineConfigurationConfigurer 注册到引擎。
 */
@Component
public class GlobalWorkflowEventListener implements FlowableEventListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalWorkflowEventListener.class);

    private final WorkflowSseService sseService;

    public GlobalWorkflowEventListener(WorkflowSseService sseService) {
        this.sseService = sseService;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event instanceof FlowableEngineEvent engineEvent)) {
            return;
        }
        if (!(event.getType() instanceof FlowableEngineEventType type)) {
            return;
        }
        String processInstanceId = engineEvent.getProcessInstanceId();
        if (processInstanceId == null) {
            return;
        }

        try {
            switch (type) {
                case PROCESS_COMPLETED -> {
                    log.info("流程完成: processInstanceId={}", processInstanceId);
                    sseService.push(processInstanceId,
                            WorkflowEvent.of(WorkflowEvent.PROCESS_COMPLETED, processInstanceId));
                }
                case PROCESS_CANCELLED -> {
                    log.info("流程取消: processInstanceId={}", processInstanceId);
                    sseService.push(processInstanceId,
                            WorkflowEvent.of(WorkflowEvent.PROCESS_CANCELLED, processInstanceId));
                }
                case JOB_EXECUTION_FAILURE -> {
                    String error = "节点异步任务执行失败";
                    if (event instanceof FlowableExceptionEvent exceptionEvent
                            && exceptionEvent.getCause() != null) {
                        error = exceptionEvent.getCause().getMessage() != null
                                ? exceptionEvent.getCause().getMessage()
                                : exceptionEvent.getCause().getClass().getName();
                    }
                    log.warn("流程 Job 执行失败: processInstanceId={}, error={}", processInstanceId, error);
                    sseService.push(processInstanceId,
                            WorkflowEvent.of(WorkflowEvent.PROCESS_FAILED, processInstanceId)
                                    .withError(error));
                }
                default -> { /* 其他事件不关心 */ }
            }
        } catch (Exception e) {
            // isFailOnException=false，异常仅记录，不影响引擎
            log.error("处理 Flowable 事件失败: type={}, processInstanceId={}, error={}",
                    type, processInstanceId, e.getMessage());
        }
    }

    @Override
    public boolean isFailOnException() {
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
