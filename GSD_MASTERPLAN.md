# 🚀 GCP Recommender 월별 최신 스냅샷(당월 부분 삭제 & 인서트) 파이프라인 GSD 마스터플랜

본 문서는 GCP Recommender API 권고사항 데이터를 BigQuery에 적재할 때, 전체 테이블을 TRUNCATE하여 과거 월(8월 등) 데이터가 소실되던 문제를 해결하고, **과거 월 데이터는 보존하면서 당월(YYYY-MM) 데이터만 매일 덮어쓰기(DELETE & INSERT)**하여 '월별 단 하나의 최신 스냅샷'을 안전하게 유지하기 위한 실행 계획서입니다.

---

## 📌 핵심 설계 및 안전 규칙

1. **TRUNCATE 명령어 완전 제거:**
   - 전체 테이블을 비우던 `TRUNCATE TABLE daily_recommender_inventory`를 완전히 제거합니다.
2. **당월(YYYY-MM) 기준 타겟팅 부분 삭제(DELETE):**
   - 배치 실행 시점의 기준일자(`snapshotDate`, 예: `2026-09-07`)의 연/월(`2026-09`)과 일치하는 데이터만 `DELETE` 쿼리로 선행 삭제합니다.
   - SQL: `DELETE FROM [dataset.daily_recommender_inventory] WHERE STARTS_WITH(CAST(snapshot_date AS STRING), @yearMonthPrefix)`
3. **과거 월(8월 등) 데이터 완벽 보존:**
   - 8월(`2026-08`), 7월(`2026-07`) 등 이전 월의 최종 스냅샷 데이터는 절대 삭제되지 않고 영구 보존됩니다.
4. **타 배치 영향 격리:**
   - 자산(`daily_asset_inventory`), Jira(`jira_issue_inventory`), 예약(`daily_reservation_inventory`) 등 다른 배치의 날짜별 누적(Append) 로직은 전혀 건드리지 않습니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: Recommender 배치 및 날짜 스키마 탐색 (repomix)] (완료)
  ├─ 1.1 daily_recommender_inventory 테이블의 날짜 기준 컬럼 snapshot_date(DATE) 확인
  └─ 1.2 전체 TRUNCATE로 인한 과거 월 데이터 소실 원인 규명
                   │
                   ▼
[Phase 2: 당월 부분 삭제(DELETE) 로직 구현 (GSD)] (진행 중)
  ├─ 2.1 BigQueryBatchService에서 TRUNCATE 로직 제거
  ├─ 2.2 deleteCurrentMonthDailyRecommenders(String yearMonthPrefix) 신설
  ├─ 2.3 runDailySnapshotBatch() 진입 시 당월(YYYY-MM) 기준 DELETE 1회 실행 연동
  └─ 2.4 타 배치 테이블 무결성 확인
                   │
                   ▼
[Phase 3: 과거 월(8월) 보존 및 당월(9월) 덮어쓰기 로컬 검증]
  ├─ 3.1 BigQueryRecommenderMonthlySnapshotTest 단위 테스트 작성
  ├─ 3.2 8월 샘플 데이터(2026-08-31) 삽입 후 9월 배치 실행
  └─ 3.3 8월 데이터 보존 확인 & 9월 최신 데이터 단일 스냅샷 갱신 쿼리 실측 검증
                   │
                   ▼
[Phase 4: 형상 관리 및 커밋]
  ├─ 4.1 fix/recommender-monthly-snapshot 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 최종 완료 보고
```
