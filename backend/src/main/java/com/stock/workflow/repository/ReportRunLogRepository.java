package com.stock.workflow.repository;

import com.stock.workflow.entity.WorkflowRunLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 报告库专用只读查询仓库（与 {@link WorkflowRunLogRepository} 同实体、独立文件，避免并行改动冲突）。
 * <p>列表页聚合到"运行"粒度（process_instance_id 分组），预览列用 SUBSTRING 投影，
 * 避免对 LONGTEXT 输出全量加载。</p>
 */
public interface ReportRunLogRepository extends JpaRepository<WorkflowRunLog, Long> {

    /** 运行级汇总投影（GROUP BY process_instance_id） */
    interface RunSummaryRow {
        String getProcessInstanceId();
        Long getWorkflowDefId();
        LocalDateTime getStartTime();
        LocalDateTime getEndTime();
        Long getNodeCount();
        Long getFailedCount();
        Long getRunningCount();
    }

    /** 最终输出预览投影（仅取前 200 字符，不拉全文） */
    interface PreviewRow {
        String getProcessInstanceId();
        String getPreview();
    }

    /**
     * 分页检索运行级汇总，按开始时间倒序。
     * filterDefIds/filterPids 为 false 时对应 IN 条件不生效（调用方传入非空哨兵列表防止空 IN）。
     */
    @Query(value = """
            SELECT l.processInstanceId AS processInstanceId,
                   MIN(l.workflowDefId) AS workflowDefId,
                   MIN(l.startedAt) AS startTime,
                   MAX(l.completedAt) AS endTime,
                   COUNT(DISTINCT l.nodeId) AS nodeCount,
                   SUM(CASE WHEN l.status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount,
                   SUM(CASE WHEN l.status = 'RUNNING' THEN 1 ELSE 0 END) AS runningCount
            FROM WorkflowRunLog l
            WHERE (:filterDefIds = FALSE OR l.workflowDefId IN :defIds)
              AND (:filterPids = FALSE OR l.processInstanceId IN :pids)
              AND (:startTime IS NULL OR l.startedAt >= :startTime)
              AND (:endTime IS NULL OR l.startedAt <= :endTime)
            GROUP BY l.processInstanceId
            ORDER BY MIN(l.startedAt) DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT l.processInstanceId)
            FROM WorkflowRunLog l
            WHERE (:filterDefIds = FALSE OR l.workflowDefId IN :defIds)
              AND (:filterPids = FALSE OR l.processInstanceId IN :pids)
              AND (:startTime IS NULL OR l.startedAt >= :startTime)
              AND (:endTime IS NULL OR l.startedAt <= :endTime)
            """)
    Page<RunSummaryRow> findRunSummaries(@Param("filterDefIds") boolean filterDefIds,
                                         @Param("defIds") Collection<Long> defIds,
                                         @Param("filterPids") boolean filterPids,
                                         @Param("pids") Collection<String> pids,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         Pageable pageable);

    /**
     * 当前页运行的输出预览（前 200 字符）。按 startedAt 升序返回，
     * 调用方按 processInstanceId 后写覆盖即得"最终节点"预览。
     * 原生 SQL：Hibernate 6 禁止对 CLOB/LONGTEXT 字段在 JPQL 中用 SUBSTRING。
     */
    @Query(value = """
            SELECT l.process_instance_id AS "processInstanceId",
                   SUBSTRING(l.output_text, 1, 200) AS "preview"
            FROM workflow_run_log l
            WHERE l.process_instance_id IN (:pids) AND l.output_text IS NOT NULL
            ORDER BY l.started_at ASC, l.id ASC
            """, nativeQuery = true)
    List<PreviewRow> findOutputPreviews(@Param("pids") Collection<String> pids);

    /** 单次运行全部节点日志（含 LONGTEXT 全文，仅详情/导出使用） */
    List<WorkflowRunLog> findByProcessInstanceIdOrderByStartedAtAscIdAsc(String processInstanceId);
}
