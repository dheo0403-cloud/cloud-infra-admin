# 🚀 GCP Active Assist 권고사항 대상 리소스(target_resource_name) 전구간 파이프라인 연동 GSD 마스터플랜

본 문서는 GCP Recommender API에서 수집되는 권고사항 데이터의 대상 리소스 식별자(Target Resource Name)를 BigQuery 테이블 스키마에 추가하고, 수집-적재-조회-프론트엔드 UI 뱃지 렌더링에 이르는 전구간 파이프라인을 완전 연동하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: repomix 기반 원인 분석 및 파싱 경로 설계] (완료)
  ├─ 1.1 GcpRecommenderService: targetResources, operationGroups, description 3단계 추출 로직 확인
  ├─ 1.2 BigQueryBatchService: daily_recommender_inventory 테이블 내 target_resource_name 컬럼 누락 식별
  ├─ 1.3 MonthlyReportService: SELECT 쿼리 및 DTO 매핑 시 대상 리소스명 누락 분석
  └─ 1.4 GcpMonthlyReportViewPage: 프론트엔드 [대상: xxx] 뱃지 렌더링 규격 정의
                   │
                   ▼
[Phase 2: DB 스키마 추가 및 데이터 파이프라인 전구간 수정] (진행 중)
  ├─ 2.1 BigQueryBatchService: daily_recommender_inventory DDL에 target_resource_name 추가 및 ALTER TABLE 안전 마이그레이션
  ├─ 2.2 BigQueryBatchService: insertDailyRecommenderBatch에 targetResourceName, priority 동적 바인딩
  ├─ 2.3 MonthlyReportService: fetchAllDailyRecommenders에서 target_resource_name 쿼리 및 formatRecommendationText 연동
  └─ 2.4 GcpMonthlyReportViewPage: [대상: xxx] 태그 감지 및 인디고 큐브 뱃지 직관적 UI 렌더링 고도화
                   │
                   ▼
[Phase 3: 수집 배치 1회 수동 트리거 및 Chrome CDP E2E 검증]
  ├─ 3.1 Spring Boot 백엔드 컴파일 & Gradle bootJar 패키징
  ├─ 3.2 수집 배치 1회 수동 트리거 및 BigQuery target_resource_name 적재 실시간 로그 확인
  ├─ 3.3 로컬 8080 서버 재기동 및 GCP 리포트 화면 접속
  └─ 3.4 Chrome CDP 기반 권고사항 리스트 내 [대상: 리소스명] 뱃지 렌더링 실측 100% 검증
                   │
                   ▼
[Phase 4: GitHub 형상 관리 및 작업 이력 저장]
  ├─ 4.1 feature/recommender-resource-mapping 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 완료 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `BigQueryBatchService.java` 스키마 및 적재 로직 수정
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`
- **구현 세부사항:**
  1. `ensureDailyRecommenderTableExists()` DDL에 `target_resource_name STRING` 추가.
  2. 기존 생성된 테이블을 위한 `ALTER TABLE `%s.%s.daily_recommender_inventory` ADD COLUMN IF NOT EXISTS target_resource_name STRING` 추가.
  3. `insertDailyRecommenderBatch` 파라미터에 `String targetResourceName` 추가 및 `rowContent.put("target_resource_name", targetResourceName)`.
  4. GCP Recommender 배치 수집 루프(라인 974)에서 `rec.getPriority()`와 `rec.getTargetResource()`를 전달.

### Task 2: `MonthlyReportService.java` 조회 및 텍스트 조합 로직 수정
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/MonthlyReportService.java`
- **구현 세부사항:**
  1. `fetchAllDailyRecommenders()`의 `selectFields`에 `target_resource_name` 추가.
  2. `row.get("target_resource_name")` 값이 존재하면 이를 우선 사용하고, 없으면 `extractTargetFromDescription`으로 Fallback.
  3. `GcpRecommenderService.formatRecommendationText(prio, targetRes, koreanDesc)`로 규격화하여 리스트에 적재.

### Task 3: `GcpMonthlyReportViewPage.tsx` 프론트엔드 뱃지 렌더링 고도화
- **수정 대상 파일:**
  - `frontend/src/pages/GcpMonthlyReportViewPage.tsx`
- **구현 세부사항:**
  1. `renderRecommendationItem`에서 `[대상: xxx]` 및 `[Target: xxx]`를 정밀 파싱.
  2. 리소스명을 깔끔한 인디고 큐브 뱃지(`<i className="fas fa-cube mr-1"></i>{targetTag}`)로 렌더링.

### Task 4: 통합 빌드, 수동 배치 트리거 및 E2E 검증
- **수행 작업:**
  - `bootJar` 패키징 및 백엔드 8080 서버 재배포
  - 수동 배치 트리거 후 BigQuery `daily_recommender_inventory` 적재 검증
  - Chrome CDP Headless로 대시보드 권고사항 리스트 내 리소스 뱃지 실측 E2E 검증
  - `feature/recommender-resource-mapping` 브랜치 커밋 및 `WORK_HISTORY.md` 기록
