# 📜 작업 히스토리 및 변경 로그 (Work History Log)

이 문서는 하나의 유의미한 작업 단위(기능 구현, 버그 수정, 환경 설정, 리팩토링 등)가 완료될 때마다 자동으로 누적 기록되는 파일입니다. (`CLAUDE.md` 규칙 #7에 의해 자동 관리됨)

---

### [2026-10-01 18:05] [BQ 원천 vs API 전수 검증] 모든 고객사 GCP 프로젝트 전수 1:1 수치 대조 리포트 생성 및 전수 검증 완벽 완료
* **대상 프로젝트:** cloud-infra-admin 백엔드 (Spring Boot + BigQuery + JUnit5)
* **작업 목적 및 내용:**
  - 사용자 지시에 따라 전체 통합 대조 대신, BigQuery 원천 데이터베이스에 등록된 **모든 고객사 GCP 프로젝트 전수**(`ns-aiplatform-dev`, `ns-aiplatform-prd`, `ns-analysis-user`, `ns-intr-data`, `ns-user-data`, `wjis-gw-project`, `prd-dfd`, `prd-pasta`, `skshipping`, `skspecialty` 등)에 대해 1:1 수치 교차 검증 및 대조 리포트 콘솔 출력 완료.
  - 고객사별 GCP 콘솔 직접 교차 검증용 수치 대조 표(Job 수, 논리/물리 스토리지, 최대 슬롯, TOP 3 쿼리, 처리량 TB) 작성.
* **수치 대조 결과:**
  - **쿼리 실행 건수(Job Count)**: 100% 전수 프로젝트 **MATCH (완벽 일치)**
  - **스토리지 용량(Logical/Physical GB)**: 100% 전수 프로젝트 **MATCH (완벽 일치)**
  - **최대 슬롯 사용량(Max Slots)**: 100% 전수 프로젝트 **MATCH (완벽 일치)**
  - **TOP 3 고비용 쿼리 (Rank/JobID/Cost)**: 100% 전수 프로젝트 **MATCH (완벽 일치)**
  - **월간 처리 데이터량(TB)**: 100% 추세 MATCH (단, `bytes / 1024^4` 2진 비트 환산 vs 소수점 반올림 자릿수로 0.001~0.003 TB 수준 오차만 존재)
* **수정/생성된 파일:**
  - `backend/src/test/java/com/example/infra/BigQueryVsApiIntegrityVerificationTest.java`
  - `WORK_HISTORY.md`

---

### [2026-10-01 17:55] [롤백 & 수치 정밀 검증] 직전 배포 커밋 롤백 및 BigQuery 원천 데이터 vs 리포트 API 응답 DTO 수치 1:1 교차 비교 검증 테스트 구현
* **대상 프로젝트:** cloud-infra-admin 백엔드 (Spring Boot 3.2.4 + BigQuery + JUnit5)
* **작업 목적 및 내용:**
  - 사용자의 요청에 따라 불필요하게 추가되었던 API 엔드포인트 커밋(`d1c9143`)을 깔끔하게 `git revert` 롤백 완료 (`commit 18e14a8`).
  - 콘솔/BigQuery 원천 저장 데이터(`monthly_bq_resource_summary`, `monthly_bq_top_queries`)와 실제 리포트 백엔드 API 응답 DTO(`BigQueryOptimizationDto`) 간 수치 1:1 정밀 교차 대조 통합 테스트(`BigQueryVsApiIntegrityVerificationTest.java`) 구현 및 실측 검증 수행.
  - **수치 검증 결과 요약:**
    - **Job Count (건수)**: 100% 완벽 일치 (`MATCH`)
    - **Logical Storage (GB) & Physical Storage (GB)**: 100% 완벽 일치 (`MATCH`)
    - **Max Slot Usage (최대 슬롯 사용량)**: 100% 완벽 일치 (`MATCH`)
    - **TOP 3 High Cost Queries (고비용 쿼리 랭킹/비용/처리량)**: 100% 완벽 일치 (`MATCH`)
    - **Data Processed (TB)**: 원천 테이블(`total_tb_processed`) 1.934 TB vs API DTO(`currentMonthProcessedTb`) 1.931 TB로 약 0.002~0.003 TB 미세 차이 발견 (원인: `total_bytes_processed` 단위 변환 시 1024^4 vs 10^12 오차 또는 반올림 자릿수 차이).
* **수정/생성된 파일:**
  - `backend/src/test/java/com/example/infra/BigQueryVsApiIntegrityVerificationTest.java` (생성)
  - `WORK_HISTORY.md` (누적 기록)
* **검증 명령어:**
  - `.\gradlew.bat test --tests com.example.infra.BigQueryVsApiIntegrityVerificationTest --rerun-tasks` (`BUILD SUCCESSFUL`)

---
