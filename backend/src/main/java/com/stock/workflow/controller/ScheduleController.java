package com.stock.workflow.controller;

import com.stock.workflow.service.DailyScheduleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 盘后定时批处理管理 API：手动触发 + 状态查询。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final DailyScheduleService scheduleService;

    public ScheduleController(DailyScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** 手动触发一轮盘后批处理（异步串行执行，重入时拒绝） */
    @PostMapping("/run-now")
    public Map<String, Object> runNow() {
        return scheduleService.triggerRun("manual");
    }

    /** 查询批处理运行状态与最近一次执行摘要 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return scheduleService.status();
    }
}
