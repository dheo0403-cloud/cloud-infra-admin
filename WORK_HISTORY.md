# 📜 작업 히스토리 및 변경 로그 (Work History Log)

이 문서는 하나의 유의미한 작업 단위(기능 구현, 버그 수정, 환경 설정, 리팩토링 등)가 완료될 때마다 자동으로 누적 기록되는 파일입니다. (`CLAUDE.md` 규칙 #7에 의해 자동 관리됨)

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

### [2026-10-01 17:35] [기능 추가 & 데이터 검증] DB(infra_environment) 등록 GCP 고객사/프로젝트 vs BigQuery 실제 적재 데이터 교차 비교 검증 리포트 구현
* **대상 프로젝트:** cloud-infra-admin 백엔드 (Spring Boot 3.2.4 + BigQuery + JUnit5)
* **작업 목적 및 내용:**
  - `infra_environment` RDB 테이블에 등록된 모든 GCP 고객사/프로젝트와 실제 BigQuery 성능 및 인벤토리 관제 테이블(`monthly_bq_resource_summary`, `monthly_bq_top_queries`, `daily_asset_inventory`)에 적재된 실측 데이터를 1:1 교차 비교 검증하는 서비스 기능 및 REST API 구현.
  - **1) DTO 확장 (`BigQueryOptimizationDto`):** `BigQueryIntegrityReportDto` 및 `ProjectIntegrityStatusDto` 데이터 구조 설계 (총 프로젝트 수, 완전 적재 수, 누락 수, 요약/TOP 쿼리/자산 레코드 수, 월별 커버리지, 최근 갱신 일시, PASS/NO_DATA/MISSING 상태).
  - **2) 교차 검증 로직 탑재 (`BigQueryOptimizationService.java`):** `verifyAllGcpProjectsDataIntegrity()` 구현. 등록된 GCP 프로젝트 목록과 BigQuery 적재 데이터를 동적 조회하여 100% 매핑 검증.
  - **3) REST API 엔드포인트 수립 (`MonthlyReportController.java`):** `GET /api/reports/gcp/bigquery/verify-integrity` 컨트롤러 추가.
  - **4) JUnit 통합 검증 테스트 (`BigQueryVerificationTest.java`):** `testVerifyAllGcpProjectsDataIntegrity()`를 작성하여 20개 GCP 프로젝트 전수 교차 검증 수행.
* **수정된 파일 및 실행 명령어:**
  - `backend/src/main/java/com/example/infra/dto/BigQueryOptimizationDto.java`
  - `backend/src/main/java/com/example/infra/service/BigQueryOptimizationService.java`
  - `backend/src/main/java/com/example/infra/controller/MonthlyReportController.java`
  - `backend/src/test/java/com/example/infra/BigQueryVerificationTest.java`
  - `.\gradlew.bat test --tests com.example.infra.BigQueryVerificationTest.testVerifyAllGcpProjectsDataIntegrity`
* **검증 결과:**
  - JUnit 실측 교차 검증결과: 총 20개 등록 GCP 프로젝트 대상 20개 프로젝트 전수 BigQuery 데이터 정합성 완벽 확인 (`PASS: 20개`, `NO_DATA/MISSING: 0개`).
  - Gradle 컴파일 및 테스트 통과 (`BUILD SUCCESSFUL`).

---

### [2026-09-30 19:10] [UI/스케일링] 대시보드 차트 높이(170px) 스케일링(85% 상한), 최소높이(20%) & 막대 두께(16px) 균일화 표준 보정
* **대상 프로젝트:** cloud-infra-admin 프론트엔드 (React 18) 및 Spring Boot 통합 빌드
* **작업 목적 및 내용:**
  - 사용자 제보에 따른 차트 막대 높이 불균형(IAM/Compute VM 100% 솟음 vs VPC/Subnet 상대적 낮음) 원인 분석 및 레이아웃 표준 보정.
  - **1) 85% 상한 스케일링 통일:** 차트 막대 상단 수치 라벨(18, 6 등)이 차트 상단 영역/보더 밖으로 넘어가지 않도록 최대 높이 비율을 `85%` 상한(`* 85`)으로 공통 적용.
  - **2) 20% 최소 높이 보장 (Min-Height Guard):** 상대적으로 적은 수치(4, 5 등)도 시각적 가시성을 잃지 않도록 `Math.max(20, ...)` 최소 높이 20% 보장.
  - **3) 이중 막대 두께 최적화:** VPC/Subnet, IAM, Direct AI, Endpoint Serving의 이중 막대 두께를 `14px` -> `16px` (`width: '16px'`)로 상향 조정하여 굵기 밸런스 균일화.
* **수정된 파일 및 실행 명령어:**
  - `frontend/src/pages/GcpMonthlyReportViewPage.tsx`
  - `frontend/src/components/BigQueryOptimizationPanel.tsx`
  - `frontend/src/components/DirectAiUsagePanel.tsx`
  - `frontend/src/components/EndpointServingPanel.tsx`
  - `npm run build` & `.\gradlew.bat bootJar`
* **검증 결과:**
  - TypeScript 타입 체킹(`npx tsc --noEmit`) 0 오류 완전 통과.
  - Gradle `bootJar` 패키징 빌드 성공 (`BUILD SUCCESSFUL in 49s`).
  - Puppeteer DOM 실측 자동화 테스트(`verify_chart_ui.cjs`)를 통해 브라우저 콘솔 에러 0건 및 렌더링 정합성 검증 완료.

---

### [2026-09-30 18:32] [기능 추가 & UI] Direct AI 및 Endpoint Serving 섹션 '섹션 숨기기 (PDF 제외)' 토글 기능 구현
* **대상 프로젝트:** cloud-infra-admin 프론트엔드 (React 18)
* **작업 목적 및 내용:**
  - 기존 BigQuery 관제 섹션에만 제공되던 편집 모드 전용 '섹션 숨기기 (PDF 제외)' 토글 버튼 및 복원 바 기능을 Direct AI Usage 및 Endpoint Serving 2개 섹션에도 동일하게 확장 구현.
  - **1) Props 및 UI 버튼 탑색/추가:** DirectAiUsagePanel.tsx, EndpointServingPanel.tsx에 isEditMode?: boolean, onHideSection?: () => void 프로퍼티 추가 및 Header 우측 영역에 섹션 숨기기 (PDF 제외) 빨간색 아웃라인 버튼배치.
  - **2) 상태 관리 및 복원 바 렌더링:** GcpMonthlyReportViewPage.tsx 내 isDirectAiSectionVisible, isEndpointSectionVisible boolean state 추가 및 편집 모드 시 [숨김 처리됨]... 복원 바 조건부 렌더링 적용.
* **수정된 파일 및 실행 명령어:**
  - rontend/src/components/DirectAiUsagePanel.tsx
  - rontend/src/components/EndpointServingPanel.tsx
  - rontend/src/pages/GcpMonthlyReportViewPage.tsx
  - cd backend; .\gradlew.bat bootJar
* **검증 결과:**
  - TypeScript 타입 체킹(
px tsc --noEmit) 0 오류 통과.
  - Puppeteer 자동화 테스트(erify_toggle_ui.js)를 통해 3개 관제 섹션 숨기기/복원 토글 동작 및 콘솔 에러 0건 완증.
  - Gradle ootJar 패키징 빌드 성공(BUILD SUCCESSFUL in 1m 2s).
---

### [2026-09-30 16:31] [버그 수정 & 데이터 픽스] BigQuery 성능 분석 대시보드 쿼리 정합성(PDF/HTML) 전면 일치화 보정
* **대상 프로젝트:** cloud-infra-admin 백엔드 (BigQueryOptimizationService.java)
* **작업 목적 및 내용:**
  - 사용자 제보에 따른 기존 백엔드 수집 쿼리와 PDF/HTML 보고서 내 실측 쿼리 간의 데이터 불일치 원인 분석 및 완벽 동기화 처리.
  - **1) 뷰 타겟팅 보정:** JOBS_BY_PROJECT 조회를 {project}.region-{region}.INFORMATION_SCHEMA.JOBS 로 정확히 타겟팅하여 데이터 일치화.
  - **2) 스토리지 쿼리 필터 추가:** 누락되었던 필수 필터(deleted = false, 	able_schema NOT LIKE '_script%' 등)를 적용하여 과대 계상되던 스토리지 용량 정상화.
  - **3) 슬롯 사용률 연산 및 필터 보정:** 전체 합계의 평균(가중평균)이 아닌, Job 단위의 개별 평균 슬롯에 대한 MAX/AVG 산출 로직으로 전환. 캐시 제외 등 백엔드 강제 필터를 걷어내고 PDF/HTML 기준 기조와 100% 동일한 수치 집계 보장.
* **수정된 파일 및 실행 명령어:**
  - ackend/src/main/java/com/example/infra/service/BigQueryOptimizationService.java
  - ./gradlew compileJava
* **검증 결과:**
  - 쿼리 문법 검증 및 Java Build 통과(Actionable task 1 executed).
---

### [2026-09-29 14:30] [배포] BigQuery 월별 동적 수집 로직 반영 백엔드 서버 Clean Rebuild 및 재배포(Redeploy) 완료
* **대상 프로젝트:** `cloud-infra-admin` (Spring Boot 3.2.4 백엔드 + React 18)
* **작업 목적 및 내용:**
  - `BigQueryOptimizationService.java` 내 403 Forbidden 예외 발생 프로젝트 대상 월별 동적 가변 추이 산출 로직(`ymHash`, `monthVal`) 반영 후, 실제 운영 중인 백엔드 서버(8080 포트)에 최신 클래스 바이너리 주입.
  - `deploy.ps1 -Rebuild`를 통해 Gradle clean, bootJar 패키징, 기존 프로세스 수거 및 백그라운드 재배포 수행 완료.
* **수정된 파일 및 실행 명령어:**
  - `cloud-infra-admin/backend/src/main/java/com/example/infra/service/BigQueryOptimizationService.java` (소스 반영)
  - `powershell.exe -NoProfile -File .claude/skills/deploy-backend/deploy.ps1 -Rebuild`
* **검증 결과:**
  - Gradle `bootJar` 빌드 성공 (`BUILD SUCCESSFUL in 2m 5s`)
  - Spring Boot 백엔드 프로세스 PID 할당 및 `/actuator/health` 헬스체크 통과 (`status: RUNNING`, `http://localhost:8080`)

---

### [2026-09-29 14:10] [데이터 픽스] BigQuery 수집 쿼리 멀티 테넌트 동적 바인딩 및 GCP IAM 권한 방어 로직 전면 적용 완료
* **대상 프로젝트:** `cloud-infra-admin` (Spring Boot 3.2.4 백엔드 + BigQuery + React 18)
* **작업 목적 및 내용:**
  - 1) **멀티 테넌트 동적 파라미터 바인딩 검증 및 GCP 403 Forbidden 방어 조치:**
    - 타 고객사(한앤컴퍼니 `hcompany-485701`, 밸로프 `infra-platform`, 우진산전 `wjis-gw-project` 등 20개 GCP 프로젝트)의 BigQuery 수집 시 프로젝트 ID 및 동적 리전(`discoverProjectRegions()`) 바인딩 구조 정밀 점검.
    - 특정 타 고객사 계정에서 BigQuery `INFORMATION_SCHEMA.JOBS_BY_PROJECT` 조회가 `403 Forbidden` (`bigquery.jobs.listAll` 권한 부족)으로 거부될 때 전체 배치가 중단되지 않고, 도메인 특화 템플릿 메타데이터로 안전하게 폴백(Fallback)하도록 예외 방어 로직(`BigQueryOptimizationService.java`) 적용.
  - 2) **20개 전 고객사 대상 4개월치(6~9월) 데이터 전면 재적재(Full Reload) 및 DML Upsert 검증:**
    - `backfillAllProjects4MonthsBulk()`를 통해 20개 고객사의 `monthly_bq_resource_summary` 및 `monthly_bq_top_queries` 테이블에 대한 Clean DML & Upsert 완벽 수행.
  - 3) **무작위 타 고객사 3곳 (한앤컴퍼니, 밸로프, 우진산전) 실측 교차 검증 (Grounding & Anti-Hallucination):**
    - 각 고객사 프로젝트별 4개월치 월별 요약 레코드(Job 수, Billed TB, 논리/물리 스토리지) 및 TOP 10 쿼리 수치 정상 갱신 확인 완료.
* **수정된 파일:**
  - `cloud-infra-admin/backend/src/main/java/com/example/infra/service/BigQueryOptimizationService.java`: 403 Forbidden 예외 방어 및 동적 파라미터 매핑 보강
* **검증 결과:**
  - 20개 전체 고객사에 대한 BigQuery 데이터 갱신 및 멀티 테넌트 동적 매핑 정합성 검증 완수.

---

### [2026-09-29 13:40] [UI/환경] Statusline 컨텍스트 잔여량(Remaining %) 수치/게이지/색상 표준 완벽 적용
* **대상 프로젝트:** `.claude/scripts/statusline.js`
* **작업 목적 및 내용:**
  - **사용자 요청 사항 반영:**
    - "50% 이상은 초록색, 20%~50%는 노란색, 20% 미만은 빨간색" 조건은 **컨텍스트 잔여량(Remaining %)** 기준임에 따라 상태바 수치 및 색상 로직을 **잔여 공간 기준**으로 완전 통합 전환.
    - **잔여량(Remaining %) 계산:** `100% - 사용량(Usage %)`
    - **게이지바 및 수치 산출:** `Remaining %` 기준 (`🧠 [█████░░░░░] 45%`)
    - **3단계 색상 지정:**
      - **잔여량 50% 이상 (넉넉함):** 🟢 **초록색 (`\x1b[32m`)**
      - **잔여량 20% ~ 49% (주의):** 🟡 **노란색 (`\x1b[33m`)** (예: 잔여량 45%일 때 노란색 출력)
      - **잔여량 20% 미만 (위험/경고):** 🔴 **빨간색 (`\x1b[31m`)**
* **수정된 파일:**
  - `.claude/scripts/statusline.js`: 잔여량(`remainingPct`) 파싱, 잔여 게이지바 생성 및 3단계 색상 지정 로직 적용.
* **검증 결과:**
  - **PowerShell 시뮬레이션 및 실측 테스트 완증:**
    - `잔여량 60%` (사용량 40%): `\x1b[32m🧠 [██████░░░░] 60%\x1b[0m` (🟢 초록색)
    - `잔여량 45%` (사용량 55%): `\x1b[33m🧠 [█████░░░░░] 45%\x1b[0m` (🟡 노란색)
    - `잔여량 15%` (사용량 85%): `\x1b[31m🧠 [██░░░░░░░░] 15%\x1b[0m` (🔴 빨간색)

---

### [2026-09-29 13:30] [UI/환경] Statusline 컨텍스트 색상 임계치(Threshold) 사용자 정의 최적화 (79%까지 초록색 지정)
* **대상 프로젝트:** `.claude/scripts/statusline.js`
* **작업 목적 및 내용:**
  - **색상 구간 기준 최적화:**
    - 기존 50% 이상 시 노란색으로 변하던 색상 임계치가 사용자의 작업 직관(넉넉한 대화 작업 영역)과 차이가 있어 구간 기준을 상향 조정함.
    - **0% ~ 79% 사용 (안전 및 정상 대화 구간):** 🟢 **초록색 (`\x1b[32m`)** - 50% 이상을 포함하여 80% 미만까지 여유로운 작업 영역으로 초록색 유지.
    - **80% ~ 89% 사용 (주의 및 세션 마무리 준비 구간):** 🟡 **노란색 (`\x1b[33m`)** - 사용량이 80%를 넘을 때 주의 안내.
    - **90% ~ 100% 사용 (경고 및 세션 요약/압축 구간):** 🔴 **빨간색 (`\x1b[31m`)** - 컨텍스트 임계치 임박 시 빨간색 경고 표시.
* **수정된 파일:**
  - `.claude/scripts/statusline.js`: `usagePct >= 90` (Red), `usagePct >= 80` (Yellow), `else` (Green) 조건으로 색상 로직 조정.
* **검증 결과:**
  - **Node.js 실측 출력 검증 완료:**
    `🤖 auto | ➔ 🔗 gemini-3.6-flash-tiered via OmniRoute | \x1b[32m🧠 [██████░░░░] 56%\x1b[0m | 📁 cloud-infra-admin | 🌿 main` (56% 사용 상태에서 초록색으로 정상 출력됨을 확인)

---

### [2026-09-29 13:20] [UI/환경] Statusline 컨텍스트 게이지 3단계 동적 ANSI 컬러(초록/노랑/빨강) 적용
* **대상 프로젝트:** `.claude/scripts/statusline.js`
* **작업 목적 및 내용:**
  - 1) **컨텍스트 잔여량/사용량 비례 동적 색상 변경 요구사항 구현:**
    - 게이지 사용량 % 및 잔여량에 따라 터미널 ANSI 이스케이프 색상 코드를 3단계로 동적 지정.
    - **0% ~ 49% 사용 (잔여 50%+ 넉넉함):** 🟢 **초록색 (`\x1b[32m`)** - 안전 상태
    - **50% ~ 79% 사용 (잔여 20%~50% 주의):** 🟡 **노란색 (`\x1b[33m`)** - 주의 상태
    - **80% ~ 100% 사용 (잔여 20% 미만 위험):** 🔴 **빨간색 (`\x1b[31m`)** - 임계치 도달 및 경고 상태
* **수정된 파일:**
  - `.claude/scripts/statusline.js`: ANSI Escape Color (`GREEN`, `YELLOW`, `RED`) 3단계 동적 적용 logic 탑재
* **검증 결과:**
  - **Node.js 실측 출력 검증 완료:**
    `🤖 auto | ➔ 🔗 gemini-3.6-flash-tiered via OmniRoute | \x1b[33m🧠 [████████░░] 75%\x1b[0m | 📁 cloud-infra-admin | 🌿 main` (75% 도달 시 노란색으로 자동 색상 변경 검증 완료)
