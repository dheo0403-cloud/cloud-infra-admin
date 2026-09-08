# 🚀 대시보드 UI 고도화 & 생성형 AI 관제 패널 구축 GSD 마스터플랜

본 문서는 내일 진행할 **[과제 1: Cloud SQL 핵심 지표 N/A 뱃지 스타일링 일관성 보정]** 및 **[과제 2: GCP Vertex AI & 생성형 AI 리소스 및 비용 관제 패널 구축]**의 통합 실행 계획서입니다.

---

## 📌 과제 1: Cloud SQL 핵심 지표 '자동 백업 및 PITR 복구' N/A 뱃지 일관성 보정

### 1. 현황 및 결함 원인 분석
* **위치:** `frontend/src/pages/GcpMonthlyReportViewPage.tsx` (라인 2141~2146)
* **결함 내용:**
  - 1번 카드('고가용성 HA 구성') & 2번 카드('DB 엔진 대표 버전'): Cloud SQL 미사용(`!isSqlExist`) 시 `N/A` 텍스트에 둥근 회색 테두리 뱃지(`backgroundColor: '#f1f5f9', border: '1px solid #cbd5e1', borderRadius: '4px', padding: '2px 6px'`)가 정상 적용됨.
  - 3번 카드('자동 백업 및 PITR 복구'): 뱃지 태그 없이 단순 텍스트로 노출되어 시각적 불일치 발생.
* **조치 계획:**
  ```tsx
  {/* 변경 전 */}
  <div>
      <span style={{ fontSize: '11px', fontWeight: 800, color: isSqlExist ? '#6d28d9' : '#64748b' }}>
          {isSqlExist ? '활성화 (정상)' : 'N/A'}
      </span>
  </div>

  {/* 변경 후 (1, 2번 카드와 완벽 통일) */}
  <div>
      {isSqlExist ? (
          <span style={{ fontSize: '11px', fontWeight: 800, color: '#6d28d9' }}>
              활성화 (정상)
          </span>
      ) : (
          <span style={{ fontSize: '11px', fontWeight: 700, color: '#64748b', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', border: '1px solid #cbd5e1' }}>
              N/A
          </span>
      )}
  </div>
  ```

---

## 📌 과제 2: GCP Vertex AI & 생성형 AI 리소스 및 비용 관제 패널 구축

### 1. 핵심 목적
1. **GPU/TPU 비용 누수 원천 차단:** 트래픽이 없는데도 24시간 배포되어 과금되는 유휴(Idle) Vertex AI 엔드포인트와 GPU/TPU 자원을 실시간 감지.
2. **선제적 Quota 증설 지원:** RPM(분당 요청수) 및 TPD(일일 토큰 한도) 소진율(%)을 시각화하여 80% 이상 도달 시 사전 할당량 상향 지원.
3. **모델 비용 최적화:** 고비용 모델(`gemini-1.5-pro`) vs 저비용 모델(`gemini-1.5-flash`) vs Fine-tuned 호출 비중 모니터링.
4. **안정성 및 보안 필터 관제:** 429(Rate Limit) 에러 및 Safety Settings 차단 프롬프트 건수 감지.

### 2. 프론트엔드 그리드 레이아웃 아키텍처
* **배치 위치:** `DashboardPage.tsx` 하단 전체 영역 (Full-Width)
* **구조:** **[좌측 7컬럼 넓은 차트 영역]** + **[우측 5컬럼 3대 위젯 박스 세로 스택]**

```
+---------------------------------------------------------------------------------------------------------+
| 🤖 GCP Vertex AI & GenAI Operations Panel (생성형 AI 리소스 & 비용 관제 센터)                            |
+-------------------------------------------------------------+-------------------------------------------+
| [Left: 7 Columns]                                           | [Right: 5 Columns]                        |
|                                                             |                                           |
| 📊 1. 토큰 사용량 & API 할당량 트렌드 (선제적 Quota 관리)   | 📦 2. 비용 발생 엔드포인트 & GPU 상태     |
|   • Input / Output Token 추이 (최근 7일 / 일별 추이)        |   • 활성 엔드포인트: 4개 (⚠️ 유휴 1개 감지) |
|   • RPM (분당 요청수) 소진율: [==== 68% ====]               |   • 할당 GPU: NVIDIA L4 × 2장             |
|   • TPD (일일 토큰 한도) 소진율: [======== 82% 🚨 ========]  +-------------------------------------------+
|   • Quota 임계치(80%) 초과 시 사전 증설 알림 뱃지           | 📦 3. 주력 AI 모델 호출 비중 (비용 분석)  |
|                                                             |   • Gemini 1.5 Flash (저비용): 68% 🟢     |
|                                                             |   • Gemini 1.5 Pro (고비용): 24% 🔵       |
|                                                             |   • Custom / Fine-tuned: 8% 🟣            |
|                                                             +-------------------------------------------+
|                                                             | 📦 4. 속도 제한(429) & 안전 필터 차단     |
|                                                             |   • 429 Too Many Requests: 3건 ⚠️         |
|                                                             |   • Safety Filter 차단 프롬프트: 12건 🛡️  |
|                                                             |   • 평균 응답 지연(Latency): 420ms        |
+-------------------------------------------------------------+-------------------------------------------+
```

### 3. 백엔드 데이터 모델 (`VertexAiMetricsDto.java`)
* `dates`, `inputTokensTrend`, `outputTokensTrend` (일별 토큰 추이)
* `rpmQuotaUsagePercent`, `tpdQuotaUsagePercent`, `currentRpm`, `maxRpmQuota`, `currentTpd`, `maxTpdQuota` (Quota 메트릭)
* `totalEndpoints`, `idleEndpoints`, `allocatedGpus`, `allocatedTpus`, `estimatedHourlyCost` (엔드포인트/GPU)
* `geminiFlashRatio`, `geminiProRatio`, `fineTunedRatio`, `promptCacheHitRatio` (모델 비중)
* `rateLimit429Errors`, `safetyFilterBlocks`, `avgLatencyMs` (속도제한 및 안전필터)

---

## 📊 3. 내일 실행 로드맵 (Execution Waves)

```
[Wave 1: Cloud SQL 핵심 지표 N/A 뱃지 스타일링 보정]
  ├─ 1.1 GcpMonthlyReportViewPage.tsx 3번 카드(자동 백업 및 PITR 복구) 뱃지 스타일 적용
  └─ 1.2 1, 2, 3번 카드 시각적 일관성 100% 동기화

[Wave 2: 백엔드 DTO 및 Vertex AI 관제 API 구축]
  ├─ 2.1 backend/src/main/java/com/example/infra/dto/VertexAiMetricsDto.java 신설
  ├─ 2.2 GcpVertexAiMetricsService.java 및 GET /api/metrics/gcp/vertex-ai 엔드포인트 연동
  └─ 2.3 Quota, GPU/TPU, 모델 비중, 429 에러 데이터 연동

[Wave 3: 프론트엔드 VertexAiOperationsPanel 컴포넌트 개발]
  ├─ 3.1 frontend/src/components/VertexAiOperationsPanel.tsx 신설
  ├─ 3.2 좌측 Chart: 토큰 사용량 & RPM/TPD 소진율 프로그레스 렌더링
  ├─ 3.3 우측 Box 1: 유휴 엔드포인트 및 GPU 상태 렌더링
  ├─ 3.4 우측 Box 2: Gemini Flash vs Pro vs Custom 호출 비중 바 렌더링
  ├─ 3.5 우측 Box 3: 429 에러 및 Safety Settings 차단 건수 렌더링
  └─ 3.6 DashboardPage.tsx 하단 전체 영역에 컴포넌트 마운트

[Wave 4: 빌드 & Chrome CDP 실측 렌더링 검증]
  ├─ 4.1 npm run build (tsc && vite build) & gradle clean bootJar
  ├─ 4.2 start_backend_server.bat 백엔드 재기동
  └─ 4.3 Chrome CDP Headless 기반 Cloud SQL N/A 뱃지 및 AI 패널 실측 검증

[Wave 5: 형상 관리 및 커밋]
  ├─ 5.1 feat/vertex-ai-operations-panel 브랜치 커밋
  ├─ 5.2 WORK_HISTORY.md 및 task-observer 자동 기록
  └─ 5.3 최종 완료 보고
```
