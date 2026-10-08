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
     * 모든 고객사의 Azure 앱(mz-api*) 비밀값·인증서 만료 목록 (예약 페이지 '앱 자격 증명 만료' 탭)
     */
    @GetMapping("/app-credentials")
    public ResponseEntity<List<ReservationDto>> getAzureAppCredentials() {
        return ResponseEntity.ok(reservationService.getAzureAppCredentials());
    }

    /**
     * 수동 새로고침: 즉시 API 호출하여 BigQuery 갱신 후 결과 반환
     */
    @PostMapping("/{environmentId}/refresh")
    public ResponseEntity<List<ReservationDto>> refreshReservations(@PathVariable String environmentId) {
        return ResponseEntity.ok(reservationService.refreshReservations(environmentId));
    }

    /**
     * Azure RI & GCP CUD 약정 만료 D-30 Slack 알람 수동 실행 (실제 만료 대상이 있을 때만 발송, 가짜 데이터 없음)
     */
    @PostMapping("/trigger-d30-slack-alert")
    public ResponseEntity<Map<String, Object>> triggerD30SlackAlert() {
        int count = bigQueryBatchService.notifyReservationsD30Expiry();
        Map<String, Object> res = new HashMap<>();
        res.put("success", count >= 0);
        res.put("sentCount", Math.max(count, 0));
        res.put("message", count > 0 ? "D-30 만료 대상 " + count + "건 Slack 발송"
                : count == 0 ? "D-30 만료 대상 없음 (발송 안 함)"
                : "조회 또는 Slack 발송 실패 (웹훅 설정·로그 확인)");
        return ResponseEntity.ok(res);
    }
}
