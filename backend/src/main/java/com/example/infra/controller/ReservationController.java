package com.example.infra.controller;

import com.example.infra.dto.ReservationDto;
import com.example.infra.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

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
}
