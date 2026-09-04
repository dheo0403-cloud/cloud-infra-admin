package com.example.infra.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationDto {
    private String provider;        // AZURE / GCP
    private String reservationName; // 예약 이름
    private String status;          // ACTIVE / EXPIRED / CANCELLED
    private String startDate;       // 시작일 (YYYY-MM-DD)
    private String expiryDate;      // 만료일 (YYYY-MM-DD)
    private String plan;            // P1Y(1년) / P3Y(3년)
    private String type;            // 리소스 유형
    private String region;          // 리전
    private String scope;           // Azure: 단일구독/공유, GCP: 프로젝트
    private String resourceDetail;  // 상세 리소스 정보
    private String projectId;       // GCP 프로젝트 또는 Azure 구독 ID
    private String customerName;    // 고객사명
    private String snapshotDate;    // 수집일
}
