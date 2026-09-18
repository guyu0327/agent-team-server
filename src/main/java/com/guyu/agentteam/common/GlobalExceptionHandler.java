package com.guyu.agentteam.common;

import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.service.AppLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppLogService appLogs;

    public GlobalExceptionHandler(AppLogService appLogs) {
        this.appLogs = appLogs;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest req) {
        appLogs.record(AppLog.TYPE_API_ERROR, null, null, req.getMethod() + " " + req.getRequestURI()
                + " → " + e.getStatus() + " " + e.getMessage());
        return build(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        appLogs.record(AppLog.TYPE_API_ERROR, null, null, req.getMethod() + " " + req.getRequestURI() + " → 400 请求体格式错误");
        return build(400, "请求体格式错误");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        appLogs.record(AppLog.TYPE_API_ERROR, null, null, req.getMethod() + " " + req.getRequestURI() + " → 400 参数类型错误");
        return build(400, "参数类型错误");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        return build(404, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e, HttpServletRequest req) {
        log.error("未处理异常", e);
        String detail = String.valueOf(e.getMessage() == null ? e : e.getMessage());
        appLogs.record(AppLog.TYPE_API_ERROR, null, null, req.getMethod() + " " + req.getRequestURI() + " → 500 " + detail);
        return build(500, "服务器内部错误");
    }

    private ResponseEntity<Map<String, Object>> build(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", status, "message", message));
    }
}
