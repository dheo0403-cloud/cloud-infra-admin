---
status: investigating
trigger: "[cloud-infra-admin] BigQuery 멀티 테넌트 재적재 불일치 버그: '전 고객사 대상 재적재'(backfillAllProjects4MonthsBulk 등)를 실행했다고 보고했으나, 실측 확인 결과 NSMall(ns-user-data) 프로젝트만 콘솔과 데이터가 일치하고 나머지 고객사(한앤컴퍼니 hcompany-485701, 밸로프 infra-platform, 우진산전 wjis-gw-project 등)의 BigQuery 데이터는 과거의 잘못된 값 그대로 남아있거나 갱신되지 않음. 의심 원인: 적재/쿼리 로직에서 project ID(ns-user-data) 또는 region(asia-northeast3 등)이 하드코딩되어 있거나, 고객사 메타데이터(Project ID/Region)를 동적으로 바인딩해 순회하는 멀티 테넌트 루프 로직에 결함이 있을 가능성."
created: 2026-09-28T05:17:31Z
updated: 2026-09-28T05:17:31Z
---

## Current Focus
<!-- OVERWRITE on each update - always reflects NOW -->

hypothesis: backfillAllProjects4MonthsBulk() (또는 관련 재적재 서비스)의 멀티 테넌트 순회 로직에서 project ID/region이 NSMall(ns-user-data, asia-northeast3)로 하드코딩되어 있거나, 순회 루프가 첫 번째 고객사 이후 조기 종료/예외 삼킴(silent catch)되어 나머지 19개 고객사에는 실제로 쿼리가 실행되지 않음
test: backend/src/main/java 내 BigQuery 배치/재적재 서비스 소스에서 project ID·region 하드코딩 여부 및 for/forEach 순회 로직의 예외 처리(try-catch) 구조를 검사. 이후 실제 BigQuery INFORMATION_SCHEMA 쿼리로 각 고객사 테이블의 최신 updated_at/건수를 실측하여 어느 고객사가 갱신되었는지 교차 확인
expecting: 하드코딩 또는 루프 내 예외 흡수(catch 후 continue 없이 break, 혹은 로그만 남기고 조용히 스킵)가 발견되면 가설 확정. INFORMATION_SCHEMA 실측 결과 NSMall만 최신이고 나머지는 9/23 이전 시각으로 멈춰있으면 가설 강하게 뒷받침
next_action: gather initial evidence — backend에서 backfillAllProjects4MonthsBulk 및 관련 BigQuery 재적재/순회 서비스 소스 검색, 프로젝트 목록(20개 GCP 프로젝트) 로딩 방식 확인
bug_class: null
reasoning_checkpoint: null
tdd_checkpoint: null

## Symptoms
<!-- Written during gathering, then immutable -->

expected: '전 고객사 대상 재적재' 실행 시 20개 GCP 프로젝트(한앤컴퍼니 hcompany-485701, 밸로프 infra-platform, 우진산전 wjis-gw-project, NSMall ns-user-data 등) 전부의 BigQuery 데이터(4개월치 사용량/Job Count/스토리지)가 최신 콘솔 실측치로 갱신되어야 함
actual: NSMall(ns-user-data) 프로젝트만 콘솔과 데이터가 일치. 한앤컴퍼니/밸로프/우진산전 등 나머지 고객사는 과거의 잘못된 데이터로 그대로 남아있거나 갱신되지 않음
errors: 명시적 에러 메시지 없음 — 배치는 "성공" 응답을 반환했으나(2026-09-23 14:45 WORK_HISTORY 기록상 "16초 만에 성공적으로 완료"), 실제로는 일부 고객사만 반영된 조용한 부분 실패(silent partial failure)로 추정
reproduction: POST /api/reports/gcp/run-batch 호출 또는 backfillAllProjects4MonthsBulk() 직접 실행 후, 각 고객사 GCP 프로젝트의 BigQuery 테이블(usage/job/storage 관련)에서 updated_at 및 COUNT(*)를 콘솔 실측치와 비교
started: 2026-09-23 NSMall 단일 고객사 데이터 불일치 픽스 이후, '전 고객사 대상 재적재' 지시를 실행한 시점부터 (본 세션 2026-09-28 확인 시점까지 미해결로 남아있음)

## Eliminated
<!-- APPEND only - prevents re-investigating after /clear -->

## Evidence
<!-- APPEND only - facts discovered during investigation -->

## Resolution
<!-- OVERWRITE as understanding evolves -->

root_cause:
fix:
verification:
oracle_type:
files_changed: []
