package com.stock.workflow.controller;

import com.stock.workflow.service.DailyScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ScheduleController} 轻量 Web 层测试：standalone MockMvc + mock
 * {@link DailyScheduleService}（不起 Spring 上下文、不连库），只验 /run-now 与 /status 的路由和响应结构。
 */
class ScheduleControllerTest {

    private DailyScheduleService scheduleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        scheduleService = mock(DailyScheduleService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ScheduleController(scheduleService)).build();
    }

    @Test
    @DisplayName("POST /api/schedule/run-now：受理时返回 started=true + reason")
    void runNow_accepted() throws Exception {
        when(scheduleService.triggerRun("manual"))
                .thenReturn(Map.of("started", true, "reason", "批处理已受理"));

        mockMvc.perform(post("/api/schedule/run-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.reason").value("批处理已受理"));
        verify(scheduleService).triggerRun("manual");
    }

    @Test
    @DisplayName("POST /api/schedule/run-now：运行中重入返回 started=false")
    void runNow_rejectedWhileRunning() throws Exception {
        when(scheduleService.triggerRun("manual"))
                .thenReturn(Map.of("started", false, "reason", "上一轮批处理仍在运行中，请稍后再试"));

        mockMvc.perform(post("/api/schedule/run-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(false));
    }

    @Test
    @DisplayName("GET /api/schedule/status：返回 running 标志 + lastRun 摘要结构")
    void status_returnsRunningAndLastRun() throws Exception {
        Map<String, Object> lastRun = new LinkedHashMap<>();
        lastRun.put("state", "DONE");
        lastRun.put("source", "cron");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("running", false);
        resp.put("lastRun", lastRun);
        when(scheduleService.status()).thenReturn(resp);

        mockMvc.perform(get("/api/schedule/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.lastRun.state").value("DONE"))
                .andExpect(jsonPath("$.lastRun.source").value("cron"));
    }
}
