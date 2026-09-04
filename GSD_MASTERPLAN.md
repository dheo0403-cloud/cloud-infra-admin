# 🚀 Jira 보고서 하위 작업(Sub-task) 제외 쿼리 필터링 GSD 마스터플랜

본 문서는 `cloud-infra-admin`의 **GCP REPORT > 기술지원/작업 내역** 화면에서 Jira 프로젝트의 상위 이슈 외에 팀원들이 추가한 하위 작업(`issue_type = '하위작업'`)까지 함께 노출되어 작업 내역이 중복 집계되는 문제를 해결하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: Jira 보고서 조회 로직 탐색 및 필터링 설계] (완료)
  ├─ 1.1 BigQuery schema 및 jira_issue_inventory 내 issue_type='하위작업' 확인
  └─ 1.2 MonthlyReportService.java의 fetchJiraIssues 조회 쿼리 분석 및 WHERE 조건 도출
                   │
                   ▼
[Phase 2: 백엔드 조회 쿼리 및 매핑 로직 수정] (진행 중)
  ├─ 2.1 MonthlyReportService.java: SQL 조건절에 issue_type NOT IN ('하위작업', ...) 추가
  ├─ 2.2 Row 매핑 루프 내 Java 방어 로직 추가 (2중 방어 필터)
  └─ 2.3 단위 테스트 스위트 (JiraSubtaskFilterTest.java) 작성 및 검증
                   │
                   ▼
[Phase 3: 빌드 검증 및 백엔드 재기동]
  ├─ 3.1 백엔드 컴파일 및 빌드 (./gradlew bootJar) 검증
  └─ 3.2 로컬 8080 서버 무중단 재기동 및 헬스체크 확인
                   │
                   ▼
[Phase 4: Git 형상 관리 및 완료 보고]
  ├─ 4.1 fix/jira-report-exclude-subtask 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 결과 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `MonthlyReportService.java` BigQuery 조회 쿼리 리팩토링
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/MonthlyReportService.java`
- **구현 세부사항:**
  1. BigQuery SQL WHERE 조건절에 `issue_type` 하위 작업 제외 조건 추가:
     ```sql
     AND (issue_type IS NULL OR (
          TRIM(CAST(issue_type AS STRING)) NOT IN ('하위작업', '하위 작업', 'Sub-task', 'Subtask')
          AND UPPER(TRIM(CAST(issue_type AS STRING))) NOT IN ('SUB-TASK', 'SUBTASK', 'SUB_TASK')
     ))
     ```
  2. Java 결과 반복 처리(`tableResult.iterateAll()`) 시에도 `issue_type`이 하위작업에 해당하는 경우 건너뛰는 2차 필터링 적용.

### Task 2: 단위 테스트 작성 및 정합성 검증
- **생성 대상 파일:**
  - `backend/src/test/java/com/example/infra/JiraSubtaskFilterTest.java`
- **검증 내용:**
  - 상위 작업과 하위 작업이 혼재된 모의 Jira 데이터 세트에서 하위 작업이 정상적으로 제외되고 상위 프로젝트 작업만 100% 선별되는지 단위 테스트 검증.

### Task 3: 프로젝트 빌드 및 Git 브랜치 형상 관리
- **수행 작업:**
  - `./gradlew bootJar` 실행
  - `start_backend_server.bat`를 통한 백엔드 서버(8080) 재기동
  - `fix/jira-report-exclude-subtask` 브랜치 커밋 및 보고
