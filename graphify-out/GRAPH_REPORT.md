# Graph Report - cloud-infra-admin  (2026-08-28)

## Corpus Check
- 66 files · ~66,875 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 623 nodes · 1514 edges · 28 communities (26 shown, 2 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 131 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- com.google.auth.oauth2.GoogleCredentials
- GcpResourceFetcher
- lombok.extern.slf4j.Slf4j
- api.ts
- MonthlyReportService
- org.springframework.http.ResponseEntity
- InfraCustomer
- package.json
- ReservationService
- InfraEnvironment
- compilerOptions
- org.junit.jupiter.api.Test
- org.springframework.stereotype.Service
- application.yml (백엔드 설정)
- lombok.RequiredArgsConstructor
- VertexAiGeminiService
- BigQueryConfig.java
- CloudProject
- GcpRecommenderService
- InfraEnvironmentRepository
- ExcelExportService
- compilerOptions
- CloudInfraAdminApplication
- gradlew
- deploy.py

## God Nodes (most connected - your core abstractions)
1. `InfraAuditReport` - 56 edges
2. `GcpAuditService` - 56 edges
3. `GcpResourceFetcher` - 47 edges
4. `InfraAuditDetail` - 46 edges
5. `InfraEnvironment` - 37 edges
6. `InfraEnvironmentRepository` - 25 edges
7. `MonthlyReportService` - 24 edges
8. `BigQueryBatchService` - 23 edges
9. `AuditController` - 22 edges
10. `InfraCustomer` - 22 edges

## Surprising Connections (you probably didn't know these)
- `정적 리소스 서빙 (classpath:/static/)` --references--> `index.html (프론트엔드 진입점)`  [INFERRED]
  BUILD.md → frontend/index.html
- `application.yml (백엔드 설정)` --conceptually_related_to--> `Cloud Infra Admin 빌드 가이드`  [INFERRED]
  backend/src/main/resources/application.yml → BUILD.md
- `JAR 패키징 (bootJar)` --shares_data_with--> `application.yml (백엔드 설정)`  [INFERRED]
  BUILD.md → backend/src/main/resources/application.yml
- `프론트엔드 빌드 (npm run build = tsc && vite build)` --shares_data_with--> `index.html (프론트엔드 진입점)`  [INFERRED]
  BUILD.md → frontend/index.html
- `JAR 패키징 (bootJar)` --shares_data_with--> `index.html (프론트엔드 진입점)`  [INFERRED]
  BUILD.md → frontend/index.html

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **전체 빌드 파이프라인 (프론트엔드 → 백엔드 → JAR)** — build, build_flow, frontend_build, gradle_task, jar_packaging, static_resource_serving, frontend_index, backend_src_main_resources_application [INFERRED 0.90]

## Communities (28 total, 2 thin omitted)

### Community 0 - "com.google.auth.oauth2.GoogleCredentials"
Cohesion: 0.06
Nodes (33): Address, InfraAuditDetail, AllArgsConstructor, Builder, Getter, NoArgsConstructor, Setter, InfraAuditReport (+25 more)

### Community 1 - "GcpResourceFetcher"
Cohesion: 0.05
Nodes (24): BigQueryBatchService, Struct, GcpResourceFetcher, ForwardingRule, com.google.cloud.compute.v1.UrlMap, com.google.iam.admin.v1.ServiceAccount, com.google.iam.admin.v1.ServiceAccountKey, Commitment (+16 more)

### Community 2 - "lombok.extern.slf4j.Slf4j"
Cohesion: 0.09
Nodes (20): GlobalExceptionHandler, GetMapping, PostMapping, RequestMapping, RestController, JiraBatchController, JiraDailyBatchScheduler, BigQuery (+12 more)

### Community 3 - "api.ts"
Cohesion: 0.13
Nodes (31): App(), MainLayout(), AzureRegularReportPage(), AzureVmCheckPage(), DashboardPage(), CudCommitment, GcpMonthlyReportViewPage(), MonthlyReportData (+23 more)

### Community 4 - "MonthlyReportService"
Cohesion: 0.11
Nodes (18): PostMapping, RequestMapping, RestController, MonthlyReportController, CudCommitmentDto, AllArgsConstructor, Builder, Getter (+10 more)

### Community 5 - "org.springframework.http.ResponseEntity"
Cohesion: 0.11
Nodes (14): AuditController, BigQuery, CrossOrigin, GetMapping, PostMapping, RequestMapping, RestController, InfraAuditDetailRepository (+6 more)

### Community 6 - "InfraCustomer"
Cohesion: 0.11
Nodes (18): InfraCustomerController, CrossOrigin, GetMapping, PostMapping, RequestMapping, RestController, InfraCustomer, AllArgsConstructor (+10 more)

### Community 7 - "package.json"
Cohesion: 0.07
Nodes (28): axios, dependencies, axios, react, react-dom, react-router-dom, devDependencies, @types/react (+20 more)

### Community 8 - "ReservationService"
Cohesion: 0.14
Nodes (12): PostMapping, RequestMapping, RestController, ReservationController, AllArgsConstructor, Builder, Getter, NoArgsConstructor (+4 more)

### Community 9 - "InfraEnvironment"
Cohesion: 0.12
Nodes (11): DeleteMapping, GetMapping, PostMapping, InfraEnvironment, AllArgsConstructor, Builder, Getter, NoArgsConstructor (+3 more)

### Community 10 - "compilerOptions"
Cohesion: 0.09
Nodes (21): compilerOptions, allowJs, allowSyntheticDefaultImports, forceConsistentCasingInFileNames, isolatedModules, jsx, lib, module (+13 more)

### Community 11 - "org.junit.jupiter.api.Test"
Cohesion: 0.14
Nodes (9): DateTimeParseTest, BigQuery, FieldValueList, PptxExportServiceTest, ReadTemplateTest, TestAcl, com.example.infra.service.PptxExportService, org.junit.jupiter.api.BeforeEach (+1 more)

### Community 12 - "org.springframework.stereotype.Service"
Cohesion: 0.16
Nodes (9): EncryptionService, ScheduledReportService, SslCertVerifyTest, com.google.cloud.compute.v1.SslCertificate, javax.annotation.PostConstruct, org.springframework.boot.test.context.SpringBootTest, org.springframework.scheduling.annotation.Scheduled, org.springframework.security.crypto.encrypt.TextEncryptor (+1 more)

### Community 13 - "application.yml (백엔드 설정)"
Cohesion: 0.19
Nodes (15): application.yml (백엔드 설정), BigQuery 데이터셋 설정, Black Dashboard React UI 테마, Cloud Infra Admin 빌드 가이드, 빌드 파이프라인 흐름, 암호화 키 보안 설정, 프론트엔드 빌드 (npm run build = tsc && vite build), index.html (프론트엔드 진입점) (+7 more)

### Community 14 - "lombok.RequiredArgsConstructor"
Cohesion: 0.20
Nodes (9): InfraEnvironmentController, CrossOrigin, DeleteMapping, RequestMapping, RestController, InfraEnvironmentService, com.google.iam.v1.Policy, lombok.RequiredArgsConstructor (+1 more)

### Community 16 - "BigQueryConfig.java"
Cohesion: 0.26
Nodes (9): BigQueryConfig, WebConfig, com.google.cloud.bigquery.BigQuery, org.springframework.context.annotation.Bean, org.springframework.context.annotation.Configuration, org.springframework.core.io.Resource, org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry, org.springframework.web.servlet.config.annotation.WebMvcConfigurer (+1 more)

### Community 17 - "CloudProject"
Cohesion: 0.20
Nodes (9): CloudProject, AllArgsConstructor, Builder, Getter, NoArgsConstructor, Setter, BigQuery, BigQuery (+1 more)

### Community 18 - "GcpRecommenderService"
Cohesion: 0.21
Nodes (6): GcpRecommendation, GcpRecommenderService, com.fasterxml.jackson.databind.ObjectMapper, lombok.AllArgsConstructor, lombok.Data, lombok.NoArgsConstructor

### Community 19 - "InfraEnvironmentRepository"
Cohesion: 0.27
Nodes (3): InfraEnvironmentRepository, BigQuery, LBAuditTest

### Community 20 - "ExcelExportService"
Cohesion: 0.33
Nodes (5): ExcelExportService, CellStyle, IndexedColors, Sheet, Workbook

### Community 21 - "compilerOptions"
Cohesion: 0.25
Nodes (7): compilerOptions, allowSyntheticDefaultImports, composite, module, moduleResolution, include, vite.config.ts

### Community 22 - "CloudInfraAdminApplication"
Cohesion: 0.60
Nodes (3): CloudInfraAdminApplication, org.springframework.boot.autoconfigure.SpringBootApplication, org.springframework.scheduling.annotation.EnableScheduling

### Community 23 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **48 isolated node(s):** `name`, `private`, `version`, `type`, `dev` (+43 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GcpResourceFetcher` connect `GcpResourceFetcher` to `com.google.auth.oauth2.GoogleCredentials`, `lombok.extern.slf4j.Slf4j`, `ReservationService`, `org.junit.jupiter.api.Test`, `org.springframework.stereotype.Service`?**
  _High betweenness centrality (0.094) - this node is a cross-community bridge._
- **Why does `InfraEnvironment` connect `InfraEnvironment` to `com.google.auth.oauth2.GoogleCredentials`, `GcpResourceFetcher`, `InfraCustomer`, `ReservationService`, `org.junit.jupiter.api.Test`, `org.springframework.stereotype.Service`, `lombok.RequiredArgsConstructor`, `CloudProject`, `InfraEnvironmentRepository`?**
  _High betweenness centrality (0.068) - this node is a cross-community bridge._
- **Why does `GcpAuditService` connect `com.google.auth.oauth2.GoogleCredentials` to `GcpResourceFetcher`, `org.springframework.stereotype.Service`, `org.springframework.http.ResponseEntity`, `lombok.RequiredArgsConstructor`?**
  _High betweenness centrality (0.067) - this node is a cross-community bridge._
- **What connects `name`, `private`, `version` to the rest of the system?**
  _48 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `com.google.auth.oauth2.GoogleCredentials` be split into smaller, more focused modules?**
  _Cohesion score 0.062280701754385964 - nodes in this community are weakly interconnected._
- **Should `GcpResourceFetcher` be split into smaller, more focused modules?**
  _Cohesion score 0.05129561078794289 - nodes in this community are weakly interconnected._
- **Should `lombok.extern.slf4j.Slf4j` be split into smaller, more focused modules?**
  _Cohesion score 0.08888888888888889 - nodes in this community are weakly interconnected._