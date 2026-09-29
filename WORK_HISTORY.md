# 📜 작업 히스토리 및 변경 로그 (Work History Log)

이 문서는 하나의 유의미한 작업 단위(기능 구현, 버그 수정, 환경 설정, 리팩토링 등)가 완료될 때마다 자동으로 누적 기록되는 파일입니다. (`CLAUDE.md` 규칙 #7에 의해 자동 관리됨)

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
