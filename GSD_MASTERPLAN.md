# 🚀 대시보드 사이드바 미니 토글(Mini Sidebar Toggle) 및 반응형 레이아웃 구현 GSD 마스터플랜

본 문서는 Cloud Infra Admin 대시보드의 좌측 사이드바('Black Dashboard' 메뉴 영역)를 접고 펼칠 수 있는 토글(Toggle) 기능과 메인 화면의 유연한 반응형 확장을 구현하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: repomix 기반 프론트엔드 레이아웃 컴포넌트 탐색 및 구조 분석] (완료)
  ├─ 1.1 frontend/index.html 스타일시트 및 CSS 변수/클래스 분석
  ├─ 1.2 frontend/src/layouts/MainLayout.tsx DOM 구조 분석
  └─ 1.3 미니 사이드바(80px) 축소형 아키텍처 및 듀얼 토글 UX 설계
                   │
                   ▼
[Phase 2: 사이드바 토글 상태 연동 및 CSS 트랜지션 애니메이션 구현] (진행 중)
  ├─ 2.1 MainLayout.tsx: isSidebarOpen (기본값 true) 상태 및 로컬스토리지 유지 로직 추가
  ├─ 2.2 MainLayout.tsx: 상단 Navbar 헤더 햄버거 토글 버튼 및 사이드바 상단 닫기/열기 버튼 구현
  ├─ 2.3 index.html / MainLayout.tsx: .sidebar-wrapper-panel.mini 및 .main-panel-content.expanded 스타일 정의
  └─ 2.4 텍스트 페이드아웃, 아이콘 중앙 정렬, 부드러운 transition(0.3s) 애니메이션 적용
                   │
                   ▼
[Phase 3: UI 동작 및 E2E 반응형 검증 (playwright / Puppeteer)]
  ├─ 3.1 프론트엔드/백엔드 로컬 서버 상태 점검
  ├─ 3.2 Puppeteer / Chrome 자동화 스크립트 작성 및 렌더링/토글 클릭 시뮬레이션
  ├─ 3.3 사이드바 폭(240px ➔ 80px) 및 메인 패널 폭/마진(280px ➔ 120px) 실측 검증
  └─ 3.4 콘솔 에러 0건 및 반응형 레이아웃 정합성 확인
                   │
                   ▼
[Phase 4: GitHub 형상 관리 및 작업 이력 저장]
  ├─ 4.1 feature/sidebar-toggle 브랜치 생성 및 변경 파일 커밋
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 완료 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: `MainLayout.tsx` 사이드바 상태 및 토글 버튼 구현
- **수정 대상 파일:**
  - `frontend/src/layouts/MainLayout.tsx`
- **구현 세부사항:**
  1. `isSidebarOpen` 상태 선언 (기본값: `localStorage.getItem('sidebar_open') !== 'false'`).
  2. 토글 함수 `toggleSidebar()` 구현 및 로컬스토리지 동기화.
  3. 사이드바 최상단 로고 영역에 미니 접기/펼치기 버튼 (`◀` / `▶` 아이콘) 추가.
  4. 메인 패널 상단 Navbar 헤더 좌측에 햄버거 토글 버튼 (`☰` / `fas fa-bars`) 추가.
  5. 사이드바가 미니 상태일 때 클래스 `.mini-sidebar` 조건부 부착 및 메인 패널에 `.expanded-panel` 조건부 부착.

### Task 2: `index.html` CSS 스타일 및 애니메이션 정의
- **수정 대상 파일:**
  - `frontend/index.html`
- **구현 세부사항:**
  1. `.sidebar-wrapper-panel`: `transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);` 적용.
  2. `.sidebar-wrapper-panel.mini-sidebar`:
     - `width: 80px;` 로 축소.
     - `.logo-text`, `.nav-header`, `.nav-link p`: `opacity: 0; visibility: hidden; width: 0; display: none;` 처리.
     - `.nav-link`: `justify-content: center; padding: 12px 0; margin: 4px 10px;`로 아이콘 중심 정렬.
     - `.nav-link i`: `margin-right: 0; font-size: 1.25rem;`
     - `.logo`: `justify-content: center; padding: 20px 0; margin: 0 10px;`
  3. `.main-panel-content`: `transition: margin-left 0.3s cubic-bezier(0.4, 0, 0.2, 1);` 적용.
  4. `.main-panel-content.expanded-panel`:
     - `margin-left: 120px;` 로 메인 영역 160px 대폭 확장.
  5. 토글 버튼 호버 및 액티브 시 Black Dashboard 시그니처 글로우 효과 부여.

### Task 3: 자동화 UI 검증 및 형상 관리
- **검증 및 커밋:**
  - Puppeteer/Playwright로 토글 전/후 DOM boundingClientRect 실측
  - `feature/sidebar-toggle` 브랜치에 커밋 및 `WORK_HISTORY.md` 기록
