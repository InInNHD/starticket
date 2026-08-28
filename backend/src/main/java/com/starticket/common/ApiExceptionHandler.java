package com.starticket.common;

import org.springframework.http.ProblemDetail;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiProblems problems;

    ApiExceptionHandler(ApiProblems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception, HttpServletRequest request) {
        return problems.create(exception.getStatus(), "请求处理失败", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, "请求参数不合法",
                "请求参数校验失败", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.CONFLICT, "请求冲突", "数据已存在或与当前状态冲突", request);
    }

    @ExceptionHandler({ServletRequestBindingException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ProblemDetail handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return problems.create(HttpStatus.BAD_REQUEST, "请求参数不合法", "请求格式或参数类型不正确", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unexpected request failure", exception);
        return problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误",
                "请求处理失败，请使用 requestId 联系管理员", request);
    }
}
