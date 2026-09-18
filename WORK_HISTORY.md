# 작업 이력 (WORK_HISTORY.md)

## [2026-09-18] GCP Vertex AI 토큰 및 엔드포인트 멀티 테넌트 수집 파이프라인 전면 교정 및 BQ 적재 검증

### 1. 작업 목적 및 개요
- **단일 프로젝트 수집 버그 원인 규명 및 전면 교정:**
  - 기존 Generative AI 토큰 수집뿐만 아니라 온라인 예측 엔드포인트 수집 파이프라인에서 일부 프로젝트만 수집되거나 수집 로직이 누락되었던 문제를 해결.
  - `GcpResourceFetcher.java`에 Cloud Monitoring API(`prediction/online/request_count`, `prediction/online/prediction_latencies`, 응답 코드 등) 기반 엔드포인트 실데이터 수집 메서드(`getVertexEndpointMetricsData`) 구현.
  - `BigQueryBatchService.java`에 `collectAndInsertDailyVertexEndpointMetrics`, `deleteDailyVertexEndpointMetrics`, `cleanPastMonthlyVertexEndpointSnapshots`, `cleanAndResyncAllVertexAiAndEndpointMetrics` 메서드를 신설 및 배치 루프(`runDailySnapshotBatch`) 연동.
- **전체 테넌트 루프 순회 및 BigQuery 전수 적재:**
  - 전체 고객사 환경(`InfraEnvironment` ➔ `CloudProject`)을 누락 없이 순회하며 `daily_vertex_ai_metrics`(토큰/Quota)와 `daily_vertex_endpoint_metrics`(엔드포인트 관제) 두 테이블 모두 20개 GCP 프로젝트 전수 수집 및 적재 완료.
- **프론트엔드 UI 연동 및 헤드리스 실측 검증:**
  - `GcpMonthlyReportViewPage.tsx` 내 Section 6(토큰 및 Quota) 및 Section 6-2(엔드포인트 관제) 연동.
  - Puppeteer 기반 `verify-ui` 스크립트로 브라우저 렌더링 및 콘솔 에러 0건 정상 검증.

### 2. 수정 및 생성된 파일 목록
1. `backend/src/main/java/com/example/infra/service/GcpResourceFetcher.java`:
   - `VertexEndpointItemCollectedData` DTO 신설
   - `getVertexEndpointMetricsData(GoogleCredentials credentials, String projectId)` Cloud Monitoring 기반 지표 수집기 구현
2. `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`:
   - `collectAndInsertDailyVertexEndpointMetrics` 및 `deleteDailyVertexEndpointMetrics` 구현 (멱등성 보장)
   - `cleanPastMonthlyVertexEndpointSnapshots` 스냅샷 정리 로직 구현
   - `cleanAndResyncAllVertexAiAndEndpointMetrics` 1회성/보정 수집 메서드 구현
   - `runDailySnapshotBatch` 내 21-2번 엔드포인트 수집 루프 연동
3. `backend/src/test/java/com/example/infra/BigQueryDataCorrectionExecutionTest.java`:
   - 토큰 및 엔드포인트 전수 재적재 및 BigQuery 쿼리 교차 검증 테스트 업데이트
4. `frontend/package.json` & `frontend/package-lock.json`:
   - `puppeteer-core` devDependency 추가

### 3. 검증 결과
- **BigQuery 적재 교차 검증 (20개 프로젝트 전수 적재 확인):**
  - `daily_vertex_ai_metrics`: 20개 프로젝트 각 1건 적재 (한앤컴퍼니 5개, 밸로프 1개, NS Mall 10개, 카카오헬스케어 3개, 우진산전 1개)
  - `daily_vertex_endpoint_metrics`: 20개 프로젝트 각 1건 적재 (동일 20개 프로젝트 전수 완료)
- **API 실서버 응답 검증:**
  - `GET /api/metrics/gcp/vertex-ai?projectId=ns-aiplatform-prd` ➔ 200 OK 정상 반환
  - `GET /api/metrics/gcp/vertex-endpoints?projectId=ns-aiplatform-prd` ➔ 200 OK 정상 반환
- **UI 검증 (`verify-ui`):**
  - Puppeteer 기반 헤드리스 크롬 실측 렌더링 성공, 콘솔 에러 0건 (`hasTokenCard: true`, `hasEndpointCard: true`).

---

### 1. 작업 목적 및 개요
- **Vertex AI 커스텀 모델 엔드포인트 수집 파이프라인 및 BigQuery 스키마 확장:**
  - 기존 Generative AI 토큰 중심 관제 외에, 커스텀 모델 온라인 예측(Online Prediction) 서빙 엔드포인트의 전용 지표(예측 요청수, 지연 시간, P95, 4XX/5XX 에러, GPU/CPU 부하, 복제본 수, 서빙 비용 등)를 수집 및 관리하기 위해 BigQuery에 `daily_vertex_endpoint_metrics` 테이블을 신설하고 실데이터 56건 적재.
- **백엔드 DTO 및 REST API 확장:**
  - `VertexEndpointMetricsDto.java` DTO 및 `GcpVertexAiMetricsService.java`에 `getVertexEndpointOperationsMetrics` 메서드 구현.
  - `GcpMetricsController.java`에 `GET /api/metrics/gcp/vertex-endpoints` API 신설.
- **프론트엔드 전용 대시보드 UI 컴포넌트 추가:**
  - `VertexEndpointOperationsPanel.tsx` 컴포넌트를 설계 및 구현하여 7일 예측 요청수, 평균 추론 지연시간(P95 포함), 가용성 성공률, 인프라 비용 요약 칩, 7일 복합 차트, 배포된 엔드포인트 목록 카드를 직관적으로 렌더링.
  - `GcpMonthlyReportViewPage.tsx`에 마운트하여 월간 보고서 화면과 완벽 연동.

### 2. 수정 및 생성된 파일 목록
1. `backend/src/main/java/com/example/infra/dto/VertexEndpointMetricsDto.java` (신규)
2. `backend/src/main/java/com/example/infra/service/GcpVertexAiMetricsService.java` (수정)
3. `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java` (수정)
4. `backend/src/main/java/com/example/infra/controller/GcpMetricsController.java` (수정)
5. `frontend/src/services/api.ts` (수정)
6. `frontend/src/components/VertexEndpointOperationsPanel.tsx` (신규)
7. `frontend/src/pages/GcpMonthlyReportViewPage.tsx` (수정)

### 3. 검증 결과
- **BigQuery 스키마 및 데이터 적재:** `daily_vertex_endpoint_metrics` 테이블 생성 및 6개 프로젝트 대상 총 56건 실데이터 적재 완료.
- **백엔드 & 프론트엔드 빌드:** `./gradlew clean bootJar` 및 `npm run build` 100% 성공.
- **API 실서버 호출 검증:** `GET /api/metrics/gcp/vertex-endpoints?projectId=ns-aiplatform-prd&targetYearMonth=2026-09` 100% 정상 반환 (7일 예측 요청수: 10,318,000건, 평균 지연: 67ms, 활성 엔드포인트: 3개, 서빙 비용: $3.88/hr).

---

## [2026-09-17] GCP 월간 보고서 강제 새로고침(Force Refresh) 기능 구현 및 BigQuery 데이터 적재 파이프라인 점검

### 1. 작업 목적 및 개요
- **보고서 강제 새로고침(Force Refresh) 기능 추가:**
  - `MonthlyReportService`의 30분 TTL 인메모리 캐시(`reportCache`)로 인해 DB 데이터 갱신 후에도 과거 데이터가 출력되던 문제를 해결.
  - 프론트엔드(`GcpMonthlyReportViewPage.tsx`)에 '데이터 새로 고침'(`btn-refresh`) 버튼을 추가하고, 클릭 시 `forceRefresh=true` 파라미터를 전송하여 캐시를 무시하고 BigQuery에서 실시간 Direct Fetch 후 캐시를 갱신하는 파이프라인 구축.
- **BigQuery 인증 및 팩트 체크:**
  - 갱신된 `gcp-credentials.json`(`mzc-monitoring@mzc-gcp-managed.iam.gserviceaccount.com`)을 적용하여 `mzc-gcp-managed.infra_admin_dataset` 10개 전체 테이블 접근 확인.
  - 7월 자산 누락(VM 0개, Disk 0개) 및 Vertex AI 동일값 복제 현황을 BigQuery 쿼리를 통해 실측 검증.

### 2. 수정된 파일 목록
1. `backend/src/main/java/com/example/infra/controller/MonthlyReportController.java`:
   - `@RequestParam(required = false, defaultValue = "false") boolean forceRefresh` 및 `force_refresh` 수신 처리.
   - `monthlyReportService.generateMonthlyReport(customerName, projectId, targetYearMonth, isForceRefresh)` 호출.
2. `backend/src/main/java/com/example/infra/service/MonthlyReportService.java`:
   - `generateMonthlyReport` 오버로딩 및 `forceRefresh=true` 시 `reportCache.remove(cacheKey)` 및 BigQuery 실시간 재조회 로직 구현.
3. `frontend/src/pages/GcpMonthlyReportViewPage.tsx`:
   - '보고서 생성' 버튼 옆에 '데이터 새로 고침' 버튼 신설.
   - `fetchReport(true)` 호출 시 `&forceRefresh=true` 동봉 및 `refreshing` 스피너 상태 처리.
   - `.btn-refresh` 스타일 추가 (초록색 톤 #059669).

### 3. 검증 결과
- **백엔드 빌드:** `./gradlew clean bootJar` 100% 빌드 성공.
- **프론트엔드 빌드:** `npm run build` (TypeScript + Vite) 100% 성공.
- **API 실서버 호출 검증:** `http://localhost:8080/api/reports/gcp/monthly?projectId=ns-aiplatform-prd&targetYearMonth=2026-08&forceRefresh=true` 호출 시 BigQuery 5개 쿼리 병렬 비동기 조회 및 DTO 100% 정상 반환 (소요시간 11.4초).

---

## [2026-09-16] Vertex AI 새로고침 버튼 제거, 일일 수집 배치 영구 수정 및 BQ 데이터 적재 경로 점검

### 1. 작업 목적 및 개요
- **Vertex AI UI 컴포넌트 정제:** `VertexAiOperationsPanel.tsx`에서 고객사명 뱃지 및 '새로고침' 버튼 UI를 완전히 삭제하고, 연관된 `isRefreshing` 상태 및 핸들러 함수를 제거하여 대시보드 UI를 간결하고 안정적인 뷰로 정제.
- **BigQuery 적재 경로 및 일일 수집 배치 검증:** `mzc-gcp-managed.infra_admin_dataset` 내 `daily_vertex_ai_metrics`, `daily_asset_inventory`, `daily_recommender_inventory`, `daily_reservation_inventory` 테이블의 적재 경로를 추적 및 규명하고, 일일 배치(`BigQueryBatchService.java`)의 고객사별 순회 수집 및 당일 선행 삭제(멱등성 보장) 로직이 영구 반영되었음을 검증.

### 2. 수정된 파일 목록
1. `frontend/src/components/VertexAiOperationsPanel.tsx`:
   - 고객사명 뱃지 및 새로고침 버튼 UI 제거
   - `isRefreshing` 상태 및 `setIsRefreshing` 로직 완전 제거
2. `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`:
   - 일일 배치 파이프라인(`runDailySnapshotBatch`) 내 고객사별 순회 수집, 당일 멱등성 보장(`deleteDailyVertexAiMetrics`), 과거 월 1건 스냅샷 압축(`cleanAllPastMonthlySnapshots`) 로직 영구 검증
3. `backend/src/main/java/com/example/infra/service/GcpVertexAiMetricsService.java`:
   - Project ID 누락 시 명시적 빈 데이터 DTO 반환

### 3. 검증 결과
- **전체 빌드:** `./gradlew clean bootJar` (TypeScript + Vite + Spring Boot bootJar 패키징) 100% 빌드 성공 (1m 5s)
- **GitHub 배포:** `git push origin main`

---

## [2026-09-16] GCP 멀티 테넌트 데이터 정합성 보정 및 LB 500 에러 과대계상 필터 교정

### 1. 작업 목적 및 개요
- **LB HTTP 500 에러 불일치 해결:** Cloud Monitoring API 및 Cloud Logging 쿼리에서 `response_code_class = "500"`(5XX 전체)으로 집계되어 502/503/504 에러까지 중복 과대 계상되던 문제를 `metric.label.response_code = "500"` 및 `httpRequest.status = 500`으로 교정하고 UI 표기를 `HTTP 500`으로 일관되게 정렬.
- **7월 데이터 누락 보정 (Backfill):** BigQuery `daily_asset_inventory` 테이블의 7월 누락 데이터(IAM 주체별 변동, Compute VM, Persistent Disk 등)를 8월 최신 스냅샷 기반으로 `2026-07-31`자 데이터로 백필하는 스크립트 실행 및 정합성 확보.
- **AI 데이터 교차 오염(Data Leakage) 차단:** 타겟 프로젝트 미지정 시 특정 고객사(`hcompany-485701`)로 fallback되던 결함을 제거하고 빈 데이터(0값)를 명시적으로 반환하도록 수정하여 멀티 테넌트 격리 보장 및 BQ 데이터 전량 재수집.
- **Recommender 데이터 부재 원인 규명:** 특정 고객사의 추천 데이터 부재는 배치 실패가 아닌 GCP Active Assist에서 진단한 최적화 대상(유휴 자원/보안 취약점)이 0건인 정상/우수 상태임을 분석 규명.

### 2. 수정된 파일 목록
1. `backend/src/main/java/com/example/infra/service/GcpResourceFetcher.java`:
   - Cloud Logging: `httpRequest.status=500` 정밀 필터 적용
   - Cloud Monitoring: `metric.label.response_code = "500"` 정밀 필터 적용 (5XX 전체 클래스 과대 계상 원천 차단)
2. `backend/src/main/java/com/example/infra/service/GcpVertexAiMetricsService.java`:
   - `targetProjectId` 누락 시 특정 테넌트(`hcompany-485701`)로 fallback하던 로직 제거 및 빈 데이터 DTO 반환
3. `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`:
   - 7월 자산 데이터 백필(`backfillJulyAssetData`), AI 데이터 BQ 전량 초기화 및 고객사별 독립 재수집(`cleanAndResyncAllVertexAiMetrics`), LB HTTP 500 에러 교정 필터 재수집(`resyncLbHttp500Metrics`) 파이프라인 탑재
4. `frontend/src/components/VertexAiOperationsPanel.tsx`:
   - 하드코딩된 fallback 테넌트 ID 제거
5. `frontend/src/pages/GcpMonthlyReportViewPage.tsx`:
   - UI 상의 `5XX` 혼재 표기를 `HTTP 500`으로 일관성 있게 정렬

### 3. 검증 결과
- **단위 테스트:** `GcpLbErrorCountTest`, `LBAuditTest` 100% 통과
- **전체 빌드:** `./gradlew clean bootJar` (TypeScript + Vite + Spring Boot bootJar 패키징) 100% 빌드 성공 (41s)
- **GitHub 배포:** `git push origin main` (커밋 해시: `8857c4d`) 푸시 완료

### 4. 후속 할 일 (Next Tasks)
- GCP 실시간 운영 배치 모니터링 및 주기적 헬스체크
