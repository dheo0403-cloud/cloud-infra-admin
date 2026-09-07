# 🚀 GCP Active Assist Recommender 최신 덮어쓰기(Truncate & Insert) 파이프라인 GSD 마스터플랜

본 문서는 GCP Recommender API 권고사항 데이터를 BigQuery에 적재할 때, 히스토리를 누적하지 않고 최신 상태만 유지(Overwrite / Truncate & Insert)하도록 파이프라인을 수정하기 위한 실행 계획서입니다.

---

## 📌 핵심 설계 및 안전 규칙

1. **Recommender 테이블 한정 TRUNCATE:**
   - 오직 `daily_recommender_inventory` 테이블에만 적용하며, 자산(`daily_asset_inventory`), Jira(`jira_issue_inventory`), 예약(`daily_reservation_inventory`) 등 다른 테이블의 날짜별 누적(Append) 로직은 절대 변경하지 않습니다.
2. **배치 시작 시점 1회 TRUNCATE 보장:**
   - 전체 GCP 환경 및 프로젝트 순회 전(배치 시작 시점)에 `TRUNCATE TABLE daily_recommender_inventory`를 1회 실행하여, 모든 프로젝트의 최신 스냅샷이 온전히 담길 수 있도록 합니다.
3. **무중단 DDL 및 안전 예외 처리:**
   - 테이블이 없거나 쿼리 실패 시에도 `ensureDailyRecommenderTableExists()`를 통해 테이블을 먼저 보장하고, 에러를 안전하게 로깅합니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: Recommender 적재 로직 분석 (repomix)] (완료)
  ├─ 1.1 BigQueryBatchService.java 내 insertDailyRecommenderBatch 스트리밍 인서트(insertAll) 방식 확인
  └─ 1.2 LoadJob 대신 DDL 쿼리(TRUNCATE TABLE)를 활용한 방법 A 채택
                   │
                   ▼
[Phase 2: BigQueryBatchService 덮어쓰기 로직 구현 (GSD)] (진행 중)
  ├─ 2.1 truncateDailyRecommenderTable() 전용 메서드 신설
  ├─ 2.2 runDailySnapshotBatch() 시작 시점에 truncateDailyRecommenderTable() 1회 호출
  └─ 2.3 타 배치(Asset, Jira, Reservation) 누적 적재 로직의 무결성 보존 확인
                   │
                   ▼
[Phase 3: 로컬 테스트 및 덮어쓰기 검증]
  ├─ 3.1 BigQueryRecommenderOverwriteTest 단위 테스트 작성 및 실행
  ├─ 3.2 TRUNCATE 실행 로그 및 최신 데이터 단일 스냅샷 적재 건수 검증
  └─ 3.3 백엔드 빌드 및 배포 정합성 확인
                   │
                   ▼
[Phase 4: 형상 관리 및 커밋]
  ├─ 4.1 feature/recommender-bq-overwrite 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 최종 브리핑
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `BigQueryBatchService.java` Recommender TRUNCATE 로직 추가
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`
- **구현 세부사항:**
  1. `truncateDailyRecommenderTable()` 메서드 작성
  2. `runDailySnapshotBatch()` 진입 시점에 `truncateDailyRecommenderTable()` 호출
  3. `daily_asset_inventory` 등 타 배치에는 일절 영향 없도록 격리

### Task 2: 로컬 단위 테스트 및 검증
- **테스트 파일:**
  - `backend/src/test/java/com/example/infra/BigQueryRecommenderOverwriteTest.java`
- **검증 내용:**
  - `truncateDailyRecommenderTable()` 호출 후 테이블 카운트 0건 확인
  - Recommender 수집 및 적재 후 최신 데이터만 정상 존재하는지 확인

### Task 3: 형상 관리 및 브리핑
- `feature/recommender-bq-overwrite` 브랜치 커밋 및 브리핑
