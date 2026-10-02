package com.example.infra.controller;

import com.example.infra.service.BigQueryOptimizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 단일 프로젝트 · 지정 월 BigQuery 성능 데이터 수집/적재 (로컬 실행 전용)
 * 인증·동시 실행 차단이 없고 수 분이 걸리는 동기 처리라, app.bq.collect-api.enabled=true 일 때만 등록된다.
 * 로컬 실행 예: 환경 변수 APP_BQ_COLLECT_API_ENABLED=true 로 기동
 */
@Slf4j
@RestController
@RequestMapping("/api/metrics/gcp")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bq.collect-api.enabled", havingValue = "true")
public class BigQueryCollectController {

    private final BigQueryOptimizationService bigQueryOptimizationService;

    // 예: POST /api/metrics/gcp/bigquery-optimization/collect?projectId=ns-user-data&months=2026-06,2026-07
    @PostMapping("/bigquery-optimization/collect")
    public ResponseEntity<Map<String, Object>> collect(@RequestParam String projectId,
                                                       @RequestParam List<String> months) throws Exception {
        log.info("[API] Collecting BigQuery optimization data for project {} months {}", projectId, months);
        return ResponseEntity.ok(bigQueryOptimizationService.collectProjectMonths(projectId, months));
    }
}
