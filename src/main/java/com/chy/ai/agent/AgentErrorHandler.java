package com.chy.ai.agent;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.chy.ai.agent")
public class AgentErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(
                        Map.of(
                                "code",
                                "HTTP_" + error.getStatusCode().value(),
                                "message",
                                error.getReason() == null ? "请求失败" : error.getReason()));
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<?> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CONFLICT", "message", "记录已存在，请刷新后重试"));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", "请求参数格式错误"));
    }
}
