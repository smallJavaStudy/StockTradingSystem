package com.stock.workflow.repository;

import com.stock.workflow.repository.ReportRunLogRepository.PreviewRow;
import com.stock.workflow.repository.ReportRunLogRepository.RunSummaryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportRunLogRepository} JPQL 切片测试（H2 MySQL 兼容模式，独立内存库，不触生产 MySQL）：
 * 验证运行级 GROUP BY 聚合、filter 标志开关、日期区间、分页 count、SUBSTRING 预览投影。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:report-repo-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class ReportRunLogRepositoryTest {

    private static final List<Long> NO_DEF_IDS = List.of(-1L);
    private static final List<String> NO_PIDS = List.of("-");

    @Autowired
    private ReportRunLogRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    private final LocalDateTime base = LocalDateTime.of(2026, 7, 20, 10, 0);

    @BeforeEach
    void seedData() {
        // 直接 JDBC 插入：绕开 @CreatedDate 审计对 started_at 的自动覆写（内存 H2，不触生产库）
        jdbc.update("DELETE FROM workflow_run_log");
        // 运行1（defId=1，两节点完成，其中最终节点输出超 200 字符）
        save("run-1", 1L, "n1", "COMPLETED", "第一节点输出", base, base.plusMinutes(1));
        save("run-1", 1L, "n2", "COMPLETED", "F".repeat(300), base.plusMinutes(1), base.plusMinutes(2));
        // 运行2（defId=2，一节点失败）
        save("run-2", 2L, "n1", "FAILED", null, base.plusHours(1), base.plusHours(1).plusMinutes(1));
        // 运行3（defId=1，运行中，不同日期）
        save("run-3", 1L, "n1", "RUNNING", null, base.plusDays(5), null);
    }

    @Test
    @DisplayName("无过滤：按运行分组聚合，时间倒序，count 正确")
    void findRunSummaries_noFilter() {
        Page<RunSummaryRow> page = repo.findRunSummaries(false, NO_DEF_IDS, false, NO_PIDS,
                null, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(RunSummaryRow::getProcessInstanceId)
                .containsExactly("run-3", "run-2", "run-1"); // MIN(startedAt) 倒序

        RunSummaryRow run1 = page.getContent().get(2);
        assertThat(run1.getWorkflowDefId()).isEqualTo(1L);
        assertThat(run1.getNodeCount()).isEqualTo(2);
        assertThat(run1.getFailedCount()).isZero();
        assertThat(run1.getRunningCount()).isZero();
        assertThat(run1.getStartTime()).isEqualTo(base);
        assertThat(run1.getEndTime()).isEqualTo(base.plusMinutes(2));

        assertThat(page.getContent().get(1).getFailedCount()).isEqualTo(1);
        assertThat(page.getContent().get(0).getRunningCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("defIds 过滤开关：filterDefIds=true 时仅返回匹配工作流的运行")
    void findRunSummaries_defIdFilter() {
        Page<RunSummaryRow> page = repo.findRunSummaries(true, List.of(2L), false, NO_PIDS,
                null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(RunSummaryRow::getProcessInstanceId)
                .containsExactly("run-2");
    }

    @Test
    @DisplayName("pids 过滤开关：filterPids=true 时仅返回指定实例")
    void findRunSummaries_pidFilter() {
        Page<RunSummaryRow> page = repo.findRunSummaries(false, NO_DEF_IDS, true, List.of("run-1", "run-3"),
                null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(RunSummaryRow::getProcessInstanceId)
                .containsExactly("run-3", "run-1");
    }

    @Test
    @DisplayName("日期区间过滤：按节点 startedAt 落区间")
    void findRunSummaries_dateRange() {
        Page<RunSummaryRow> page = repo.findRunSummaries(false, NO_DEF_IDS, false, NO_PIDS,
                base.minusHours(1), base.plusHours(2), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(RunSummaryRow::getProcessInstanceId)
                .containsExactly("run-2", "run-1"); // run-3 在 5 天后，不在区间
    }

    @Test
    @DisplayName("分页：size=2 时第二页返回剩余 1 条，总数不变")
    void findRunSummaries_pagination() {
        Page<RunSummaryRow> page1 = repo.findRunSummaries(false, NO_DEF_IDS, false, NO_PIDS,
                null, null, PageRequest.of(0, 2));
        Page<RunSummaryRow> page2 = repo.findRunSummaries(false, NO_DEF_IDS, false, NO_PIDS,
                null, null, PageRequest.of(1, 2));

        assertThat(page1.getContent()).hasSize(2);
        assertThat(page1.getTotalElements()).isEqualTo(3);
        assertThat(page2.getContent()).extracting(RunSummaryRow::getProcessInstanceId)
                .containsExactly("run-1");
    }

    @Test
    @DisplayName("预览投影：SUBSTRING 截断到 200 字符，仅返回有输出的日志")
    void findOutputPreviews_truncates() {
        List<PreviewRow> previews = repo.findOutputPreviews(List.of("run-1", "run-2"));

        assertThat(previews).hasSize(2); // run-2 无输出行不返回
        PreviewRow last = previews.get(previews.size() - 1); // 升序最后一条 = 最终节点
        assertThat(last.getProcessInstanceId()).isEqualTo("run-1");
        assertThat(last.getPreview()).hasSize(200).containsOnlyOnce("F".repeat(200));
    }

    private void save(String pid, Long defId, String nodeId, String status, String output,
                      LocalDateTime start, LocalDateTime end) {
        jdbc.update("""
                INSERT INTO workflow_run_log
                (workflow_def_id, process_instance_id, node_id, node_name, status, output_text, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, defId, pid, nodeId, nodeId + "-名称", status, output, start, end);
    }
}
