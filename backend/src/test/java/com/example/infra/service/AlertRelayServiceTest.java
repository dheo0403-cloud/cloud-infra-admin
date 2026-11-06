package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MARK type 헤더(Slack 알람 종류) 매핑과 서비스 헬스 번역 검증
 */
class AlertRelayServiceTest {

    private static AzureAlertMapper.MarkAlert alert(String signalType) {
        return new AzureAlertMapper.MarkAlert(signalType, "sub", "id", "High", "open",
            "English title", "svc", "target", 1L, "English description", "", false);
    }

    @Test
    void 서비스_헬스_중복은_고객사별로_판단() {
        var a = alert("ServiceHealth");
        assertNotEquals(AlertRelayService.dedupKey(a, "고객사 A"), AlertRelayService.dedupKey(a, "고객사 B"));
        // 같은 고객사의 다른 구독이나 Azure 재전송은 계속 중복이다
        assertEquals(AlertRelayService.dedupKey(a, "고객사 A"), AlertRelayService.dedupKey(a, "고객사 A"));
    }

    @Test
    void 서비스_헬스가_아니면_중복_기준이_그대로() {
        var a = alert("Platform");
        assertEquals("id", AlertRelayService.dedupKey(a, "고객사 A"));
        assertEquals(AlertRelayService.dedupKey(a, "고객사 A"), AlertRelayService.dedupKey(a, "고객사 B"));
    }

    @Test
    void 시간_초과면_다시_보내_성공() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = AlertRelayService.retryOnTimeout(() -> {
            if (calls.incrementAndGet() < 3) throw new HttpTimeoutException("request timed out");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void 시간_초과가_계속되면_3회_후_실패() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(HttpTimeoutException.class, () -> AlertRelayService.retryOnTimeout(() -> {
            calls.incrementAndGet();
            throw new HttpTimeoutException("request timed out");
        }));
        assertEquals(3, calls.get());
    }

    @Test
    void 시간_초과가_아닌_오류는_다시_보내지_않음() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> AlertRelayService.retryOnTimeout(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("인증서 오류");
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void 알람_종류별_MARK_type() {
        assertEquals("azure_monitor", AlertRelayService.markType("Platform"));
        assertEquals("azure_monitor", AlertRelayService.markType("Log Alerts V2"));
        assertEquals("azure_monitor", AlertRelayService.markType("Activity Log - Administrative"));
        assertEquals("azure_resource_health", AlertRelayService.markType("Resource Health"));
        assertEquals("azure_service_health", AlertRelayService.markType("ServiceHealth"));
    }

    @Test
    void MARK_성공_판정은_본문_code까지_확인() {
        assertEquals(false, AlertRelayService.markAccepted(200, "{\"code\": 500, \"message\": \"type value is Not Found or Invalid - ERROR\"}"));
        assertEquals(true, AlertRelayService.markAccepted(200, "{\"code\": 200, \"message\": \"ok\"}"));
        assertEquals(true, AlertRelayService.markAccepted(200, "OK"));
        assertEquals(false, AlertRelayService.markAccepted(500, "{\"code\": 200}"));
    }

    @Test
    void 서비스_헬스는_제목과_설명만_번역본으로_바꿈() {
        VertexAiGeminiService gemini = mock(VertexAiGeminiService.class);
        when(gemini.translateAlertToKorean(any(), any())).thenReturn(Map.of("title", "한글 제목", "description", "한글 설명"));
        var a = alert("ServiceHealth");
        var result = new AlertRelayService(null, null, gemini).translated(a);
        assertEquals("한글 제목", result.alertName());
        assertEquals("한글 설명", result.description());
        assertEquals(a.severity(), result.severity());
        assertEquals(a.alertId(), result.alertId());
    }

    @Test
    void 번역_실패나_예외면_원문으로_보냄() {
        VertexAiGeminiService gemini = mock(VertexAiGeminiService.class);
        when(gemini.translateAlertToKorean(any(), any())).thenReturn(null);
        var a = alert("ServiceHealth");
        assertSame(a, new AlertRelayService(null, null, gemini).translated(a));

        VertexAiGeminiService failing = mock(VertexAiGeminiService.class);
        when(failing.translateAlertToKorean(any(), any())).thenThrow(new RuntimeException("Vertex AI 오류"));
        assertSame(a, new AlertRelayService(null, null, failing).translated(a));
    }

    @Test
    void 서비스_헬스가_아니면_번역하지_않음() {
        VertexAiGeminiService gemini = mock(VertexAiGeminiService.class);
        var a = alert("Platform");
        assertSame(a, new AlertRelayService(null, null, gemini).translated(a));
        verifyNoInteractions(gemini);
    }

    // ---- 중복 차단: 로컬 가짜 MARK 서버로 실제 전송 흐름을 검증 ----

    private static final String SUB = "11111111-2222-3333-4444-555555555555";
    private HttpServer mark;

    @AfterEach
    void stopMark() {
        if (mark != null) mark.stop(0);
    }

    /** 호출 순번을 받아 HTTP 코드를 돌려주는 가짜 MARK에 연결된 서비스 */
    private AlertRelayService relayWithMark(IntUnaryOperator httpCodeByCall) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        mark = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mark.setExecutor(Executors.newCachedThreadPool());
        mark.createContext("/", exchange -> {
            int code = httpCodeByCall.applyAsInt(calls.incrementAndGet());
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        mark.start();

        AlertRelayService service = new AlertRelayService(mock(BigQuery.class), null, null);
        ReflectionTestUtils.setField(service, "markEnabled", true);
        ReflectionTestUtils.setField(service, "markUrl", "http://127.0.0.1:" + mark.getAddress().getPort() + "/");
        ReflectionTestUtils.setField(service, "markApiKey", "test");
        ReflectionTestUtils.setField(service, "overrideServiceId", "");
        ReflectionTestUtils.setField(service, "overrideCustomerName", "");
        ReflectionTestUtils.setField(service, "forceType", "");
        ReflectionTestUtils.setField(service, "sourceTag", "");
        ReflectionTestUtils.setField(service, "customerById", Map.of(SUB, "고객사"));
        ReflectionTestUtils.setField(service, "customerLoadedAt", System.currentTimeMillis());
        return service;
    }

    private static JsonNode metricAlert(String condition) throws Exception {
        String target = "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm";
        return new ObjectMapper().readTree("{\"schemaId\":\"azureMonitorCommonAlertSchema\",\"data\":{\"essentials\":{"
            + "\"alertId\":\"/subscriptions/" + SUB + "/providers/Microsoft.AlertsManagement/alerts/abc\","
            + "\"alertRule\":\"cpu\",\"severity\":\"Sev1\",\"signalType\":\"Metric\",\"monitorCondition\":\"" + condition + "\","
            + "\"monitoringService\":\"Platform\",\"alertTargetIDs\":[\"" + target + "\"],\"configurationItems\":[\"vm\"],"
            + "\"targetResourceType\":\"microsoft.compute/virtualmachines\",\"firedDateTime\":\"2026-10-29T01:00:00Z\","
            + "\"resolvedDateTime\":\"2026-10-29T01:30:00Z\",\"description\":\"\"},\"alertContext\":{}}}");
    }

    @Test
    void 같은_상태가_반복되면_차단하고_상태가_바뀌면_전달() throws Exception {
        AlertRelayService service = relayWithMark(call -> 200);
        assertEquals("SENT", service.relayAzure(metricAlert("Fired")));
        assertEquals("DUPLICATE", service.relayAzure(metricAlert("Fired")));
        assertEquals("SENT", service.relayAzure(metricAlert("Resolved")));
        assertEquals("DUPLICATE", service.relayAzure(metricAlert("Resolved")));
        // 복구 뒤 다시 발생하면 10분 안이어도 전달한다
        assertEquals("SENT", service.relayAzure(metricAlert("Fired")));
    }

    @Test
    void 전송이_실패하면_같은_알람이_다시_들어올_때_재시도() throws Exception {
        AlertRelayService service = relayWithMark(call -> call == 1 ? 500 : 200);
        assertEquals("FAILED", service.relayAzure(metricAlert("Fired")));
        assertEquals("SENT", service.relayAzure(metricAlert("Fired")));
    }

    @Test
    void 전송_중_들어온_같은_알람은_앞선_전송이_실패하면_대신_전송() throws Exception {
        assertEquals(List.of("FAILED", "SENT"), relayConcurrently(500));
    }

    @Test
    void 전송_중_들어온_같은_알람은_앞선_전송이_성공하면_중복() throws Exception {
        assertEquals(List.of("DUPLICATE", "SENT"), relayConcurrently(200));
    }

    /** 첫 전송이 MARK에서 멈춘 사이 같은 알람이 들어온 상황. 결과를 정렬해 돌려준다 */
    private List<String> relayConcurrently(int firstCode) throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AlertRelayService service = relayWithMark(call -> {
            if (call > 1) return 200;
            firstArrived.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return firstCode;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> service.relayAzure(metricAlert("Fired")));
            assertTrue(firstArrived.await(10, TimeUnit.SECONDS));
            Future<String> second = pool.submit(() -> service.relayAzure(metricAlert("Fired")));
            // 두 번째 요청이 처리 순서를 기다리는 동안에는 결과가 나오지 않아야 한다
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
            release.countDown();
            return Stream.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)).sorted().toList();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
