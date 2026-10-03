package com.stock.workflow.controller;

import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 工作流模块统一异常处理（仅限 com.stock.workflow.controller 包，不影响现有 Controller 行为）：
 * 业务异常转 4xx + message，避免堆栈裸奔。
 * 声明最高优先级，确保优先于全局 GlobalExceptionHandler 的 Exception 兜底拦截。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.stock.workflow.controller")
public class WorkflowControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(WorkflowControllerAdvice.class);

    /** 参数/校验类错误 → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 资源不存在 → 404 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Flowable 对象不存在（如取消不存在的流程实例）→ 404 */
    @ExceptionHandler(FlowableObjectNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleFlowableNotFound(FlowableObjectNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 状态冲突（如未发布就执行）→ 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 兜底 → 500（只露 message，不露堆栈） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("工作流接口未预期异常: {}", e.getMessage(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getMessage() != null ? e.getMessage() : "服务器内部错误");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("status", status.value());
        body.put("message", message != null ? message : status.getReasonPhrase());
        return ResponseEntity.status(status).body(body);
    }
}
