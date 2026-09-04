# 🚀 GCP Active Assist 권고사항 대상 리소스(Target Resource) 파싱 및 UI 렌더링 GSD 마스터플랜

본 문서는 `cloud-infra-admin`의 **GCP REPORT > 정기 점검 권고 사항 (GCP Active Assist)** 패널에서 권고 메시지의 조치 대상 리소스(IAM 계정, VM 인스턴스, Cloud SQL 인스턴스, 디스크 등) 식별자가 노출되지 않아 조치가 불가능하던 문제를 해결하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: Recommender API 응답 구조 분석 및 리소스 추출 설계] (완료)
  ├─ 1.1 targetResources / operations[].resource 필드 구조 분석
  └─ 1.2 [우선순위] [대상: 리소스명] 권고 메시지 표준 포맷 규격화
                   │
                   ▼
[Phase 2: 백엔드 수집 엔진 및 조회 로직 리팩토링] (진행 중)
  ├─ 2.1 GcpRecommenderService.java: targetResources 파싱 및 extractResourceName 구현
  ├─ 2.2 BigQueryBatchService.java: [대상: xxx] 리소스명이 포함된 권고 메시지 적재
  ├─ 2.3 MonthlyReportService.java: 권고사항 DTO 매핑 시 대상 리소스 태그 보존 및 정규화
  └─ 2.4 단위 테스트 스위트 (GcpRecommenderTargetResourceTest.java) 작성 및 검증
                   │
                   ▼
[Phase 3: 프론트엔드 UI 렌더링 개선 & 빌드]
  ├─ 3.1 GcpMonthlyReportViewPage.tsx: [대상: xxx] 태그/뱃지 스타일 시각화 개선
  ├─ 3.2 전체 프로젝트 빌드 (./gradlew bootJar) 및 8080 서버 재기동
                   │
                   ▼
[Phase 4: Git 형상 관리 및 완료 보고]
  ├─ 4.1 feature/recommender-target-resource 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 결과 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `GcpRecommenderService.java` 대상 리소스 파싱 엔진 탑재
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/GcpRecommenderService.java`
- **구현 세부사항:**
  1. `GcpRecommendation` 모델에 `targetResource`, `priority` 필드 추가.
  2. `fetchRealGcpRecommendations`에서 `targetResources` 배열 및 `content.operationGroups[].operations[].resource` 파싱.
  3. `extractResourceName(String uri)` 헬퍼 구현:
     - `//compute.googleapis.com/.../instances/vm-name` ➔ `vm-name`
     - `//iam.googleapis.com/.../serviceAccounts/sa@...` ➔ `sa@...`
     - `//cloudsql.googleapis.com/.../instances/sql-name` ➔ `sql-name`
     - `//compute.googleapis.com/.../disks/disk-name` ➔ `disk-name`
     - `//compute.googleapis.com/.../addresses/ip-name` ➔ `ip-name`
  4. `translateRecommendationToKorean(String desc, String targetResource)` 메서드 확장.

### Task 2: `MonthlyReportService.java` 및 `BigQueryBatchService.java` 포맷 정규화
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`
  - `backend/src/main/java/com/example/infra/service/MonthlyReportService.java`
- **구현 세부사항:**
  - `[우선순위] [대상: 리소스명] 권고 내용` 형식으로 권고사항 목록을 가공하여 프론트엔드에 전달.
  - 기존 데이터(스냅샷)에 대상 리소스 태그가 없는 경우에도 description 내 정규식 추출을 통해 대상 리소스명을 복원하는 Fallback 지원.

### Task 3: 프론트엔드 UI 태그 스타일 가독성 향상
- **수정 대상 파일:**
  - `frontend/src/pages/GcpMonthlyReportViewPage.tsx`
- **구현 세부사항:**
  - `[HIGH]`, `[MEDIUM]`, `[대상: xxx]` 접두어가 포함된 권고사항 항목을 깔끔한 태그와 텍스트로 분리 렌더링.

### Task 4: 단위 테스트 및 빌드/형상 관리
- **생성 대상 파일:**
  - `backend/src/test/java/com/example/infra/GcpRecommenderTargetResourceTest.java`
- **수행 작업:**
  - `./gradlew bootJar` 및 `start_backend_server.bat` 재기동
  - `feature/recommender-target-resource` 브랜치 커밋
