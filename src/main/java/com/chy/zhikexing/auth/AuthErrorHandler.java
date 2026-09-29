package com.chy.zhikexing.auth;

import com.chy.zhikexing.controller.UserController;
import com.chy.zhikexing.entity.vo.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserController.class)
public class AuthErrorHandler {
    @ExceptionHandler(AuthLoginGuard.Rejected.class)
    public ResponseEntity<Result> rejected(AuthLoginGuard.Rejected error) {
        return ResponseEntity.status(error.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(error.getRetryAfterSeconds()))
                .body(Result.fail(error.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Result.fail("登录服务暂时不可用，请稍后重试"));
    }
}
