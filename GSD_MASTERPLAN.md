# 🚀 GCP REPORT 리소스(PD, 스냅샷, VPN) 집계 데이터 정합성 복구 GSD 마스터플랜

본 문서는 `cloud-infra-admin`의 **GCP REPORT > Persistent Disk, 스냅샷, Cloud VPN 핵심 지표** 화면에서 밸로프(`infra-platform`) 고객사의 리소스 집계 데이터 불일치(PD 47->43, 스냅샷 56->70, VPN 1->0)를 근본적으로 해결하기 위한 실행 계획서입니다.

---

## 📊 작업 의존성 로드맵 (Dependency Graph)

```
[Phase 1: 자원별 집계 오차 원인 분석 및 정규화] (완료)
  ├─ 1.1 Persistent Disk: 비활성/삭제 중(DELETING/FAILED) 디스크 필터링 부재로 4개 과다 집계 확인
  ├─ 1.2 Snapshot: Snapshot Schedule Policy 자동 백업 및 리전/인스턴트 스냅샷 누락(14개) 확인
  └─ 1.3 Cloud VPN: 프론트엔드 displayTot 삼항 연산자 Fallback 1 하드코딩 오류 확인
                   │
                   ▼
[Phase 2: 백엔드 수집 엔진 & 프론트엔드 UI 수정] (진행 중)
  ├─ 2.1 GcpResourceFetcher.java & BigQueryBatchService.java 수정
  │    ├─ getComputeDisks: READY 상태의 유효 활성 디스크만 필터링 (47 -> 43개)
  │    └─ getComputeSnapshots: 자동 백업 정책 및 전체 스냅샷 누락 없이 포괄 수집 (56 -> 70개)
  ├─ 2.2 GcpMonthlyReportViewPage.tsx 수정
  │    └─ VPN displayTot 및 렌더링 로직 수정 (0개 시 1 치환 제거, 미사용/0개 정상 표기)
  └─ 2.3 단위 테스트 스위트 (GcpResourceCountTest.java) 신설 및 100% PASS 검증
                   │
                   ▼
[Phase 3: 프론트엔드 UI 통합 빌드 & 자동 검증]
  ├─ 3.1 프론트엔드 및 백엔드 통합 패키징 (./gradlew clean bootJar)
  ├─ 3.2 로컬 테스트 서버 렌더링 검증 (verify-ui / Puppeteer / Playwright)
  └─ 3.3 밸로프 고객사 선택 시 PD 43개, 스냅샷 70개, VPN 0개 UI 표출 검증
                   │
                   ▼
[Phase 4: Git 형상 관리 및 완료 보고]
  ├─ 4.1 fix/gcp-resource-count 브랜치 생성 및 상세 커밋 (원인 및 조치 내용 명시)
  ├─ 4.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 4.3 사용자 최종 결과 보고
```

---

## 🛠️ 세부 작업 분할 (Task Breakdown)

### Task 1: 백엔드 디스크 & 스냅샷 수집 엔진 리팩토링
- **수정 대상 파일:**
  - `backend/src/main/java/com/example/infra/service/GcpResourceFetcher.java`
  - `backend/src/main/java/com/example/infra/service/BigQueryBatchService.java`
- **구현 내용:**
  1. **Persistent Disk (47 -> 43):**
     - `getComputeDisks()`에서 `READY` 상태인 정상 디스크만 선별하거나, `DELETING`/`FAILED` 상태의 비정상 디스크를 제외하여 유효한 43개 디스크만 정확하게 수집.
     - `BigQueryBatchService.java`에서 디스크 상태 판별 강화.
  2. **Snapshot (56 -> 70):**
     - `getComputeSnapshots()`에서 표준 스냅샷뿐만 아니라 자동 백업 스냅샷(Resource Policy / Snapshot Schedule 기반 생성분) 및 리전/인스턴트 스냅샷을 포괄적으로 수집하여 총 70개 정합성 확보.

### Task 2: 프론트엔드 Cloud VPN 렌더링 결함 수정
- **수정 대상 파일:**
  - `frontend/src/pages/GcpMonthlyReportViewPage.tsx`
- **구현 내용:**
  1. `const displayTot = vpnTot > 0 ? vpnTot : (reportData.vpnTotal || 1);` 오류 코드를 `const displayTot = vpnTot;`로 정정.
  2. `displayTot === 0`일 때 'Cloud VPN 총 수량 0개 (미사용)', 연결률 '0%', '0/0개 사용 중' 등으로 정상 렌더링되도록 수정.

### Task 3: 단위 테스트 스위트 작성
- **생성 대상 파일:**
  - `backend/src/test/java/com/example/infra/GcpResourceCountTest.java`
- **검증 내용:**
  - 밸로프 모의 데이터에 대해 PD 43개, 스냅샷 70개, VPN 0개가 정확하게 계산되는지 단위 테스트 검증.

### Task 4: 통합 빌드, UI 검증 및 형상 관리
- **수행 작업:**
  - 전체 빌드 (`./gradlew clean bootJar`)
  - 로컬 8080 서버 재기동 및 UI 렌더링 확인
  - `fix/gcp-resource-count` 브랜치에 커밋
