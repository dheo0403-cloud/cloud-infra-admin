# 🚀 대시보드 지표 카드 0건/미사용 시 회색(비활성화) 조건부 스타일링 일관성 적용 GSD 마스터플랜

본 문서는 Cloud Infra Admin 리포트 대시보드 화면(`GcpMonthlyReportViewPage.tsx`)의 핵심 지표 카드들(LB, GKE, Cloud Run, Cloud SQL, Cloud VPN, VPC) 중, 자원이 0개이거나 미사용(N/A) 상태일 때 모든 지표 카드가 일관되게 회색(비활성화) 테마로 렌더링되도록 표준화하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: repomix 기반 컴포넌트 로직 분석 및 차이 식별] (완료)
  ├─ 1.1 LB/GKE 컴포넌트의 isLbExist / isGkeExist 조건부 회색 렌더링 패턴 분석
  ├─ 1.2 Cloud Run / Cloud SQL / Cloud VPN / VPC의 하드코딩된 원색 스타일 결함 식별
  └─ 1.3 표준 회색(비활성화) 디자인 토큰(#f8fafc, #e2e8f0, #94a3b8, #64748b) 규격화
                   │
                   ▼
[Phase 2: 전 지표 카드 조건부 회색 스타일링 일괄 적용] (진행 중)
  ├─ 2.1 Cloud Run 핵심 지표: isCloudRunExist (총 서비스/Job > 0) 기준 3개 카드 회색 분기
  ├─ 2.2 Cloud SQL 핵심 지표: isSqlExist (sqlTotal > 0) 기준 HA/DB엔진/백업 3개 카드 회색 분기
  ├─ 2.3 Cloud VPN 핵심 지표: isVpnExist (displayTot > 0) 기준 총 수량/암호화/연결률 카드 회색 분기
  └─ 2.4 VPC 핵심 지표: totalIp > 0 / totalFw > 0 기준 프로그레스바 및 레이블 회색 분기
                   │
                   ▼
[Phase 3: 프론트엔드 빌드 및 Playwright/Chrome CDP E2E 렌더링 검증]
  ├─ 3.1 프론트엔드 TypeScript 컴파일 & Vite 빌드
  ├─ 3.2 0건 자원(Cloud Run, Cloud SQL, Cloud VPN 등) mock 데이터 기반 DOM 실측
  ├─ 3.3 회색 배경(#f8fafc), 회색 바(#94a3b8), N/A 뱃지 및 콘솔 에러 0건 확인
  └─ 3.4 통합 패키징(bootJar) 및 8080 서버 재배포
                   │
                   ▼
[Phase 4: GitHub 형상 관리 및 작업 이력 저장]
  ├─ 4.1 fix/empty-metric-gray-styles 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 완료 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `GcpMonthlyReportViewPage.tsx` 조건부 회색 렌더링 일괄 적용
- **수정 대상 파일:**
  - `frontend/src/pages/GcpMonthlyReportViewPage.tsx`
- **구현 세부사항:**
  1. **Cloud Run 핵심 지표:**
     - `const isCrExist = (ingAll + ingInt + jobTot) > 0;` (또는 `reportData.cloudRunSummary?.totalServices > 0`)
     - 배경색: `isCrExist ? '#fffbe6' : '#f8fafc'`, 보더: `isCrExist ? '#ffe58f' : '#e2e8f0'`, 좌측바: `isCrExist ? '#d97706' : '#94a3b8'`
     - 아이콘 및 텍스트/수치: `isCrExist ? '#d97706' : '#64748b'`, 서브텍스트: `isCrExist ? ... : '미사용 (배포된 서비스 없음)'`
     - 내부 접근 서비스 & Jobs 카드도 동일하게 `isCrExist` 기준으로 비활성화 회색 처리.
  2. **Cloud SQL 핵심 지표:**
     - `const isSqlExist = sqlTot > 0;`
     - HA 구성 카드, DB 엔진 버전 카드, 자동 백업/PITR 카드 3종 모두 `isSqlExist`가 false일 때 `#f8fafc`, `#e2e8f0`, `#94a3b8`, `#64748b` 회색 적용 및 "미사용" 뱃지 표시.
  3. **Cloud VPN 핵심 지표:**
     - `const isVpnExist = displayTot > 0;`
     - 상단 요약 카드: `isVpnExist ? '#fffbeb' : '#f8fafc'`, 좌측바: `isVpnExist ? '#f59e0b' : '#94a3b8'`, 아이콘: `isVpnExist ? '#d97706' : '#64748b'`, 우측 수치: `isVpnExist ? `${displayTot}개` : '0개 (N/A)'`
     - 프로그레스 바: `isVpnExist ? '#0284c7' : '#cbd5e1'`, 텍스트: `isVpnExist ? ... : '#64748b'`
  4. **VPC 핵심 지표:**
     - IP 사용률 & 방화벽 로그율: `totalIp > 0`, `totalFw > 0`일 때만 강조색, 0일 때는 회색(`#64748b`, `#cbd5e1`) 렌더링.

### Task 2: 자동화 UI 실측 검증 및 형상 관리
- **검증 및 커밋:**
  - Chrome CDP로 0건인 프로젝트/데이터 렌더링 시 background, border, color CSS 속성 실측 검증
  - `fix/empty-metric-gray-styles` 브랜치 커밋 및 `WORK_HISTORY.md` 기록
