# 🚀 GCP Active Assist 권고사항 대상 리소스 파싱 및 세로 레이아웃(Vertical Layout) 개편 GSD 마스터플랜

본 문서는 `cloud-infra-admin`의 **GCP REPORT > 정기 점검 권고 사항 (GCP Active Assist)** 패널에서 권고 메시지의 조치 대상 리소스(IAM 계정, VM 인스턴스, Cloud SQL 인스턴스, 디스크 등) 식별자 노출 및 3개 카테고리(보안, 비용, 성능) 가로 배치(`grid-cols-3`)를 전체 너비 기반 세로 배치(`flex-col`)로 전환하여 가독성을 극대화하기 위한 종합 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: Recommender API 응답 구조 분석 및 리소스 추출 설계] (완료)
  ├─ 1.1 targetResources / operations[].resource 필드 구조 분석
  └─ 1.2 [우선순위] [대상: 리소스명] 권고 메시지 표준 포맷 규격화
                   │
                   ▼
[Phase 2: 백엔드 수집 엔진 및 조회 로직 리팩토링] (완료)
  ├─ 2.1 GcpRecommenderService.java: targetResources 파싱 및 extractResourceName 구현
  ├─ 2.2 BigQueryBatchService.java: [대상: xxx] 리소스명이 포함된 권고 메시지 적재
  ├─ 2.3 MonthlyReportService.java: 권고사항 DTO 매핑 시 대상 리소스 태그 보존 및 정규화
  └─ 2.4 단위 테스트 스위트 (GcpRecommenderTargetResourceTest.java) 작성 및 검증
                   │
                   ▼
[Phase 3: 프론트엔드 세로 레이아웃 개편 & UI 가독성 극대화] (진행 중)
  ├─ 3.1 GcpMonthlyReportViewPage.tsx: 가로 그리드(3열) 해제 ➔ 세로 스택(flex-col, 100% width) 전환
  ├─ 3.2 개별 카테고리 카드 여백(Padding/Margin) 및 간격(Gap: 16px) 최적화
  ├─ 3.3 편집 모드 textarea 및 뷰 모드 권고사항 텍스트의 가독성 개선
  └─ 3.4 빌드 및 Puppeteer/Playwright DOM 렌더링 검증
                   │
                   ▼
[Phase 4: Git 형상 관리 및 완료 보고]
  ├─ 4.1 design/recommender-vertical-layout 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 결과 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `GcpRecommenderService.java` 대상 리소스 파싱 엔진 탑재 (완료)
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

### Task 2: `MonthlyReportService.java` 및 `BigQueryBatchService.java` 포맷 정규화 (완료)
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`
  - `backend/src/main/java/com/example/infra/service/MonthlyReportService.java`
- **구현 세부사항:**
  - `[우선순위] [대상: 리소스명] 권고 내용` 형식으로 권고사항 목록을 가공하여 프론트엔드에 전달.
  - 기존 데이터(스냅샷)에 대상 리소스 태그가 없는 경우에도 description 내 정규식 추출을 통해 대상 리소스명을 복원하는 Fallback 지원.

### Task 3: 프론트엔드 UI 세로 레이아웃(Vertical Stack) 전환 및 가독성 최적화
- **수정 대상 파일:**
  - `frontend/src/pages/GcpMonthlyReportViewPage.tsx`
- **구현 세부사항:**
  1. `display: 'grid'`, `gridTemplateColumns: 'repeat(3, 1fr)'` ➔ `display: 'flex'`, `flexDirection: 'column'`, `gap: '16px'` 변경.
  2. 3개 카테고리 카드(보안/비용/성능)가 각각 가로 100%를 차지하도록 구성하여 긴 리소스명 및 상세 권고 텍스트의 줄바꿈 최소화.
  3. 각 카드의 패딩(`padding: '16px 20px'`)과 헤더 영역(뱃지, 카테고리 타이틀, 건수 표시 등) 시각적 계층 구조 강화.
  4. 편집 모드 `textarea` 높이를 가로 확장 폭에 맞게 `rows={3}`, `minHeight: '75px'`로 최적화.

### Task 4: 빌드, 자동화 UI 검증 및 형상 관리
- **수행 작업:**
  - 프론트엔드 Vite 빌드 및 백엔드 Spring Boot 패키징 (`./gradlew bootJar`)
  - Puppeteer / Playwright 자동화 스크립트를 통한 3개 카테고리 박스 세로 배치 및 텍스트 렌더링 검증
  - `design/recommender-vertical-layout` 브랜치 커밋 및 `WORK_HISTORY.md` 기록

