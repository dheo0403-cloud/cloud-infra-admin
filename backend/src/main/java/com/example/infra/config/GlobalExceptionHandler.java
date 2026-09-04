package com.example.infra.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        
        // 외부 스캐너 요청(루트 /, 워드프레스, .env 등)은 경고 로그를 발생시키지 않고 디버그 레벨로 처리
        boolean isScannerRequest = uri == null || uri.equals("/") || uri.contains("wp-") || uri.contains(".env") || uri.contains("solr");
        if (isScannerRequest) {
            log.debug("[HTTP 405 Scanner Ignored] {} {}", method, uri);
        } else {
            log.warn("[HTTP 405] Method not supported: {} {} | Supported: {}", method, uri, ex.getSupportedHttpMethods());
        }

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of(
                "status", "ERROR",
                "error", "Method Not Allowed",
                "method", method,
                "uri", uri != null ? uri : "/",
                "supportedMethods", ex.getSupportedHttpMethods() != null ? ex.getSupportedHttpMethods() : "N/A"
        ));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("[HTTP 404] Resource not found: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", "ERROR",
                "error", "Not Found",
                "message", "Resource not found: " + ex.getResourcePath(),
                "uri", request.getRequestURI()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(
            Exception ex, HttpServletRequest request) {
        log.error("[HTTP 500] Unhandled exception on {} {}: {}", 
                request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "ERROR",
                "error", ex.getMessage() != null ? ex.getMessage() : "Internal Server Error",
                "uri", request.getRequestURI() != null ? request.getRequestURI() : ""
        ));
    }
}
