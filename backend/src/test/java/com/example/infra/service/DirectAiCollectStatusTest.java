package com.example.infra.service;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.PermissionDeniedException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Direct AI 수집 상태: 권한 없음 판정과 적재 행(권한 없음이면 수치 NULL)
class DirectAiCollectStatusTest {

    @Test
    void PermissionDeniedException이면_권한_없음() {
        Exception ex = new PermissionDeniedException(new RuntimeException("denied"), GrpcStatusCode.of(Status.Code.PERMISSION_DENIED), false);
        assertTrue(GcpResourceFetcher.isPermissionDenied(ex));
    }

    @Test
    void 원인_체인의_PERMISSION_DENIED_메시지도_권한_없음() {
        Exception ex = new IllegalStateException("wrapped", new RuntimeException("PERMISSION_DENIED: Permission monitoring.timeSeries.list denied"));
        assertTrue(GcpResourceFetcher.isPermissionDenied(ex));
    }

    @Test
    void 다른_오류는_권한_없음이_아님() {
        assertFalse(GcpResourceFetcher.isPermissionDenied(new RuntimeException("DEADLINE_EXCEEDED")));
        assertFalse(GcpResourceFetcher.isPermissionDenied(null));
    }

    @Test
    void 권한_없음이면_수치는_비우고_상태만_저장() {
        GcpResourceFetcher.DirectAiCollectedData data = GcpResourceFetcher.DirectAiCollectedData.builder()
                .modelUsages(List.of())
                .collectStatus(GcpResourceFetcher.AI_COLLECT_PERMISSION_DENIED)
                .build();
        Map<String, Object> row = BigQueryBatchService.directAiRow("2026-10-20", "p1", "고객", data);
        assertEquals("PERMISSION_DENIED", row.get("collect_status"));
        assertFalse(row.containsKey("total_tokens"));
        assertFalse(row.containsKey("input_tokens"));
        assertEquals("p1", row.get("project_id"));
    }

    @Test
    void 정상_수집이면_수치와_OK_상태_저장() {
        GcpResourceFetcher.DirectAiCollectedData data = GcpResourceFetcher.DirectAiCollectedData.builder()
                .totalTokens(0L)
                .modelUsages(List.of())
                .build(); // 상태 기본값 OK
        Map<String, Object> row = BigQueryBatchService.directAiRow("2026-10-20", "p1", "고객", data);
        assertEquals("OK", row.get("collect_status"));
        assertEquals(0L, row.get("total_tokens")); // 실제 미사용은 0으로 저장
    }
}
