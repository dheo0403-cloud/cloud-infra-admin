package com.example.infra.controller;

import com.example.infra.dto.ReservationDto;
import com.example.infra.service.BigQueryBatchService;
import com.example.infra.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final BigQueryBatchService bigQueryBatchService;

    /**
     * 해당 환경의 당일 최신 예약(RI/CUD) 목록 조회 (GET / POST 모두 지원)
     */
    @RequestMapping(value = "/{environmentId}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<List<ReservationDto>> getReservations(@PathVariable String environmentId) {
        return ResponseEntity.ok(reservationService.getReservations(environmentId));
    }

    /**
     * 모든 고객사의 100일 미만 만료 예정인 예약(RI/CUD) 목록 조회 (GET / POST 모두 지원)
     */
    @RequestMapping(value = "/upcoming-expiry", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<List<ReservationDto>> getUpcomingExpiryReservations() {
        return ResponseEntity.ok(reservationService.getUpcomingExpiryReservations());
    }

    /**
     * 수동 새로고침: 즉시 API 호출하여 BigQuery 갱신 후 결과 반환
     */
    @PostMapping("/{environmentId}/refresh")
    public ResponseEntity<List<ReservationDto>> refreshReservations(@PathVariable String environmentId) {
        return ResponseEntity.ok(reservationService.refreshReservations(environmentId));
    }

    /**
     * Azure RI & GCP CUD 약정 만료 D-30 Slack 알람 1회성 테스트 발송
     */
    @PostMapping("/trigger-d30-slack-alert")
    public ResponseEntity<Map<String, Object>> triggerD30SlackAlert(@RequestParam(defaultValue = "true") boolean sendMockIfEmpty) {
        boolean success = bigQueryBatchService.checkReservationsD30ExpiryAndNotifySlack(sendMockIfEmpty);
        Map<String, Object> res = new HashMap<>();
        res.put("success", success);
        res.put("message", success ? "Slack D-30 alert triggered and sent successfully" : "Slack D-30 alert failed to send (check webhook configuration or logs)");
        return ResponseEntity.ok(res);
    }
}
