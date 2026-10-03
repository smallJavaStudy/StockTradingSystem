package com.stock.workflow.engine;

import com.stock.workflow.entity.WorkflowRunLog;
import com.stock.workflow.repository.WorkflowRunLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 节点执行日志写入器。
 * <p>
 * 所有写操作走 REQUIRES_NEW 独立事务：Delegate 在 Flowable 流程事务内执行，
 * 节点失败会导致流程事务回滚，若日志与流程共用事务则 FAILED 日志会被回滚吞掉。
 */
@Component
public class WorkflowRunLogWriter {

    private static final int ERROR_MSG_MAX_LEN = 2000;

    private final WorkflowRunLogRepository runLogRepo;
    private final TransactionTemplate requiresNewTx;

    public WorkflowRunLogWriter(WorkflowRunLogRepository runLogRepo,
                                PlatformTransactionManager transactionManager) {
        this.runLogRepo = runLogRepo;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 写入 RUNNING 日志，返回日志 id */
    public Long logStart(Long workflowDefId, String processInstanceId, String nodeId,
                         String nodeName, Long agentId, String inputText) {
        return requiresNewTx.execute(status -> {
            WorkflowRunLog runLog = new WorkflowRunLog();
            runLog.setWorkflowDefId(workflowDefId);
            runLog.setProcessInstanceId(processInstanceId);
            runLog.setNodeId(nodeId);
            runLog.setNodeName(nodeName);
            runLog.setAgentId(agentId);
            runLog.setStatus("RUNNING");
            // 入库前净化非 BMP 字符（workflow_run_log 表同为 utf8 三字节字符集）
            runLog.setInputText(WorkflowTextUtils.stripNonBmp(inputText));
            return runLogRepo.save(runLog).getId();
        });
    }

    /** 标记 COMPLETED 并写入输出全文 */
    public void logComplete(Long runLogId, String outputText) {
        requiresNewTx.executeWithoutResult(status ->
                runLogRepo.findById(runLogId).ifPresent(runLog -> {
                    runLog.setStatus("COMPLETED");
                    runLog.setOutputText(WorkflowTextUtils.stripNonBmp(outputText));
                    runLog.setCompletedAt(LocalDateTime.now());
                    runLogRepo.save(runLog);
                }));
    }

    /** 标记 FAILED 并写入错误信息（截断至列长限制） */
    public void logFail(Long runLogId, String errorMessage) {
        requiresNewTx.executeWithoutResult(status ->
                runLogRepo.findById(runLogId).ifPresent(runLog -> {
                    runLog.setStatus("FAILED");
                    runLog.setErrorMessage(truncate(WorkflowTextUtils.stripNonBmp(errorMessage)));
                    runLog.setCompletedAt(LocalDateTime.now());
                    runLogRepo.save(runLog);
                }));
    }

    private String truncate(String msg) {
        if (msg == null) return null;
        return msg.length() > ERROR_MSG_MAX_LEN ? msg.substring(0, ERROR_MSG_MAX_LEN) : msg;
    }
}
