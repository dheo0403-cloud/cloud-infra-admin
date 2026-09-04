# 🚀 GCP REPORT LB 핵심 지표 500 에러 정합성 복구 GSD 마스터플랜

본 문서는 `cloud-infra-admin`의 **GCP REPORT > LB 핵심 지표** 화면에서 밸로프(`infra-platform`) 고객사의 최근 30일 HTTP 500 에러 건수가 2배 중복 집계(1,203,702건 vs 실제 611,345건)되는 버그를 근본적으로 수정하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: 500 에러 집계 쿼리 분석 및 정규화] (완료)
  ├─ 1.1 집계 로직 위치 탐색 (GcpResourceFetcher & BigQueryBatchService)
  └─ 1.2 HTTP/HTTPS 포워딩 룰 및 Cloud Monitoring TimeSeries 중복 합산 원인 규명
                   │
                   ▼
[Phase 2: 백엔드 수집 엔진 리팩토링 & 중복 제거 로직 탑재] (현재 진행)
  ├─ 2.1 GcpResourceFetcher.java의 getLbHttp500Last30DaysCount 개선
  │    ├─ Cloud Monitoring API 호출 시 URL Map 단위 GroupBy Aggregation 적용
  │    └─ HTTP(80) / HTTPS(443) 포워딩 룰 중복 스트림 합산 제거 및 HTTPS 우선 필터링
  ├─ 2.2 Cloud Logging 필터 최적화 (resource.type 및 프론트엔드 룰 정규화)
  └─ 2.3 단위 테스트 작성 및 밸로프 실제 수치(611,345건) 정합성 검증
                   │
                   ▼
[Phase 3: 빌드 검증 및 Git 형상 관리]
  ├─ 3.1 백엔드 컴파일 및 빌드 (./gradlew bootJar) 검증
  ├─ 3.2 fix/lb-500-error-count 브랜치 생성 및 원인/해결책 커밋
  └─ 3.3 WORK_HISTORY.md 및 task-observer 로그 자동 기록
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `GcpResourceFetcher.java` 500 에러 집계 엔진 리팩토링
- **목적:** Cloud Monitoring API 쿼리 시 `Aggregation`(`groupByFields = "resource.labels.url_map_name"`, `crossSeriesReducer = REDUCE_SUM`, `perSeriesAligner = ALIGN_SUM`)을 적용하여 동일 URL Map에 매핑된 HTTP/HTTPS 포워딩 룰 간의 중복 합산 원천 차단.
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/GcpResourceFetcher.java`
- **구현 세부사항:**
  1. Cloud Logging 필터: `resource.type="http_load_balancer"` 외에 `https_lb_rule` 및 프론트엔드 로그 대상으로 정규화.
  2. Cloud Monitoring 필터: `loadbalancing.googleapis.com/https/request_count` 대상 URL Map 그룹별 고유 집계 메커니즘 구축.
  3. URL Map별 TimeSeries 집계 시 HTTP 포워딩 룰과 HTTPS 포워딩 룰이 동일 로드밸런서에 중복 발행될 경우, 상위 HTTPS 대표 스트림(또는 URL Map 단위 정규화 합산)을 산출하여 2배 뻥튀기 방지.

### Task 2: 단위 테스트 작성 및 정합성 검증
- **목적:** 포워딩 룰 2개(HTTP/HTTPS)가 연결된 로드밸런서 환경에서 500 에러 건수가 611,345건으로 정확하게 산출되는지 Mock/단위 테스트 스위트로 검증.
- **생성 파일:**
  - `backend/src/test/java/com/example/infra/GcpLbErrorCountTest.java`

### Task 3: 프로젝트 빌드 및 Git 브랜치 형상 관리
- **목적:** 백엔드 재빌드 및 `fix/lb-500-error-count` 브랜치에 상세 커밋 로그 작성.
- **수행 작업:**
  - `git init`, `git checkout -b fix/lb-500-error-count`
  - 원인 분석 및 해결책 명시한 커밋 생성
