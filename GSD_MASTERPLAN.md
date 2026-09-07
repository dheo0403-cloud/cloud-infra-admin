# 🚀 GCP 보고서 '작성 내용 저장' 버튼 및 연관 데드코드 제거 GSD 마스터플랜

본 문서는 보고서 편집 모드에서 동작하지 않던 '작성 내용 저장' 기능과 관련 UI 요소를 완전히 제거하고 프론트엔드 툴바 레이아웃을 간결화하기 위한 실행 계획서입니다.

---

## 📌 핵심 작업 목표

1. **불필요한 저장 UI 요소 완전 제거:**
   - 템플릿(JSX)에서 초록색 `[작성 내용 저장]` 버튼 요소 삭제
2. **연관 데드코드 정리:**
   - `handleSaveText` 핸들러 함수, `savingText` 상태 변수, `.btn-save` CSS 클래스 완전 제거
3. **UI 툴바 레이아웃 보존:**
   - `[보고서 생성]`, `[편집 모드/종료]`, `[PDF 저장 / 인쇄]` 버튼의 flex 레이아웃 및 간격 무결성 보존
4. **E2E 렌더링 검증:**
   - Chrome CDP Headless를 통해 편집 모드 진입 시 초록색 저장 버튼 부재 및 툴바 정상 렌더링 확인

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: UI 및 로직 정밀 스캔 (repomix)] (완료)
  ├─ 1.1 GcpMonthlyReportViewPage.tsx 내 [작성 내용 저장] JSX 버튼(라인 832) 식별
  ├─ 1.2 handleSaveText 함수(라인 338), savingText 상태(라인 167), .btn-save 스타일(라인 548) 식별
  └─ 1.3 브리핑 완료
                   │
                   ▼
[Phase 2: 버튼 및 연관 로직 제거 (GSD)] (진행 중)
  ├─ 2.1 JSX 템플릿에서 [작성 내용 저장] 버튼 요소 완전 삭제
  ├─ 2.2 handleSaveText 및 savingText 데드코드 제거
  ├─ 2.3 .btn-save CSS 클래스 삭제
  └─ 2.4 TypeScript 컴파일 & 프론트엔드 빌드 검증
                   │
                   ▼
[Phase 3: 프론트엔드 렌더링 실측 검증 (Playwright/CDP)]
  ├─ 3.1 Spring Boot / Vite 프론트엔드 패키징 & 서버 재기동
  ├─ 3.2 Chrome CDP Headless로 /gcp-report 접속 및 [편집 모드] 클릭 시뮬레이션
  └─ 3.3 초록색 버튼 부재 및 툴바 flex 레이아웃 정상 유지 100% 실측 검증
                   │
                   ▼
[Phase 4: 형상 관리 및 커밋]
  ├─ 4.1 remove/report-save-button 브랜치 생성 및 상세 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 최종 완료 보고
```
