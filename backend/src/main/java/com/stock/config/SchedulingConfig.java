package com.stock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关配置：启用 Spring @Scheduled 调度（盘后每日复盘批处理等）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
