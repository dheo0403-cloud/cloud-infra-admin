# 🚀 BigQuery 적재 대상 프로젝트(Target Project) 라우팅 오류 수정 GSD 마스터플랜

본 문서는 일일 배치(`daily_asset_inventory`, `daily_recommender_inventory`, `daily_reservation_inventory` 등) 및 리포트 조회 시 데이터 적재 대상(Target) 프로젝트가 중앙 관리 프로젝트인 `mzc-gcp-managed`가 아닌 개별 고객사 프로젝트(`msp-g2cms1-wjis-240118` 등)로 잘못 지정되어 테이블이 생성 및 적재되던 결함을 근본적으로 해결하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: repomix 기반 원인 분석 및 결함 식별] (완료)
  ├─ 1.1 TableId.of(dataset, table) 2-인자 호출로 인한 타겟 프로젝트 미지정 식별
  ├─ 1.2 DDL/DML 및 SQL 쿼리 내 2단 식별자(`dataset.table`) 사용으로 인한 컨텍스트 오염 분석
  └─ 1.3 MonthlyReportService 내 JobId.setProject(customerProjectId) 오류 식별
                   │
                   ▼
[Phase 2: BigQuery 적재/조회 서비스의 타겟 프로젝트 강제 고정 리팩토링] (진행 중)
  ├─ 2.1 BigQueryBatchService.java: targetProjectId 주입 및 TableId 3-인자(`targetProjectId, datasetName, table`) 적용
  ├─ 2.2 BigQueryBatchService.java: DDL/DML 쿼리를 3단 식별자(`targetProjectId.datasetName.table`)로 전면 수정
  ├─ 2.3 MonthlyReportService.java: 쿼리 및 JobId 생성 시 targetProjectId(`mzc-gcp-managed`)로 강제 고정
  ├─ 2.4 JiraBigQueryService.java, ReservationService.java, Repositories: TableId 및 SQL 3단 식별자 표준화
  └─ 2.5 AuditController.java: 과거 테스트 잔재 하드코딩 제거 및 동적 targetProjectId 적용
                   │
                   ▼
[Phase 3: 로컬 빌드 및 배치 1회 수동 트리거 검증]
  ├─ 3.1 Spring Boot 백엔드 컴파일 및 빌드 (./gradlew bootJar)
  ├─ 3.2 로컬 배치 수동 실행 또는 단위 테스트를 통한 mzc-gcp-managed 적재 로그 확인
  └─ 3.3 백엔드 8080 서버 재기동 및 헬스체크
                   │
                   ▼
[Phase 4: Git 형상 관리 및 완료 보고]
  ├─ 4.1 fix/bq-target-project-routing 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 결과 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `BigQueryBatchService.java` 타겟 프로젝트 고정
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`
- **구현 세부사항:**
  1. `@Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}") private String targetProjectId;` 주입.
  2. `insertDailyAssetBatch`, `insertDailyRecommenderBatch`, `insertDailyReservationBatch`의 `TableId`를 `TableId.of(targetProjectId, datasetName, tableName)`로 3-인자 수정.
  3. `ensureDailyRecommenderTableExists()`, `deleteDailyReservations()`, `cleanupDeletedData()` 내의 모든 DDL/DML 쿼리를 `` `%s.%s.%s` `` (`targetProjectId`, `datasetName`, `tableName`)으로 수정.

### Task 2: `MonthlyReportService.java` 조회 및 JobId 타겟 프로젝트 고정
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/MonthlyReportService.java`
- **구현 세부사항:**
  1. `@Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}") private String targetProjectId;` 주입.
  2. `queryWithFallback()`의 SQL을 ``SELECT ... FROM `%s.%s.%s` `` (`targetProjectId`, `datasetName`, `tableName`)으로 변경.
  3. `JobId.newBuilder().setProject(targetProjectId)`로 쿼리 실행 주체 프로젝트를 중앙 프로젝트로 고정 (고객사 `projectId` 오염 차단).

### Task 3: 기타 서비스, 리포지토리 및 컨트롤러 3단 식별자 표준화
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/JiraBigQueryService.java`
  - `backend/src/main/java/com/example/infra/service/ReservationService.java`
  - `backend/src/main/java/com/example/infra/repository/InfraAuditDetailRepository.java`
  - `backend/src/main/java/com/example/infra/repository/InfraAuditReportRepository.java`
  - `backend/src/main/java/com/example/infra/repository/InfraCustomerRepository.java`
  - `backend/src/main/java/com/example/infra/repository/InfraEnvironmentRepository.java`
  - `backend/src/main/java/com/example/infra/controller/AuditController.java`

### Task 4: 빌드, 배치 트리거 검증 및 형상 관리
- **수행 작업:**
  - Gradle 컴파일 및 단위 테스트 실행
  - 로컬에서 배치 1회 수동 트리거하여 로그 확인
  - `fix/bq-target-project-routing` 브랜치 커밋 및 `WORK_HISTORY.md` 기록


