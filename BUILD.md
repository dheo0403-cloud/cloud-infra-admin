# Cloud Infra Admin - 빌드 가이드

## 전체 빌드 프로세스

이 프로젝트는 **프론트엔드(React/Vite) → 백엔드(Spring Boot) → JAR 패키징** 순서로 빌드됩니다.

### 빌드 흐름

```
frontend/ (npm build)
    ↓ dist/ 생성
backend/ (gradle build)
    ↓ frontend/dist → backend/src/main/resources/static 복사
    ↓ bootJar 실행
build/libs/cloud-infra-admin-0.0.1-SNAPSHOT.jar 생성
```

---

## Gradle 태스크 구조 (backend/build.gradle)

```gradle
// frontend 디렉토리 경로
def frontendDir = "$projectDir/../frontend"

// 1. 프론트엔드 의존성 설치 (devDependencies 포함 필수!)
task installFrontendDependencies(type: Exec) {
    workingDir "$frontendDir"
    if (System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')) {
        commandLine 'npm.cmd', 'install', '--production=false'
    } else {
        commandLine 'npm', 'install', '--production=false'
    }
}

// 2. 프론트엔드 빌드 (npm run build = tsc && vite build)
task buildFrontend(type: Exec) {
    dependsOn installFrontendDependencies
    workingDir "$frontendDir"
    if (System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')) {
        commandLine 'npm.cmd', 'run', 'build'
    } else {
        commandLine 'npm', 'run', 'build'
    }
}

// 3. 빌드된 프론트엔드를 백엔드 static 리소스로 복사
task copyFrontendToBackend(type: Copy) {
    dependsOn buildFrontend
    from "$frontendDir/dist"
    into "$projectDir/src/main/resources/static"
    doLast {
        copy {
            from "$frontendDir/dist"
            into "$buildDir/resources/main/static"
        }
    }
}

// 4. 리소스 처리 시 프론트엔드 복사 포함
processResources {
    dependsOn copyFrontendToBackend
    exclude '**/~$*'
}

// 5. JAR 빌드 시 프론트엔드 복사 포함
bootJar {
    dependsOn copyFrontendToBackend
    archiveFileName = 'cloud-infra-admin-0.0.1-SNAPSHOT.jar'
    destinationDirectory = file("build/libs")
}
```

---

## 실행 명령어

### 전체 빌드 (프론트엔드 + 백엔드 + JAR)
```bash
cd backend
./gradlew clean bootJar
```

### 프론트엔드만 빌드
```bash
cd frontend
npm run build
```

### 백엔드만 빌드 (이미 빌드된 프론트엔드 사용)
```bash
cd backend
./gradlew bootJar
```

### JAR 실행
```bash
java -jar backend/build/libs/cloud-infra-admin-0.0.1-SNAPSHOT.jar
```

---

## 주의사항

### 1. 프론트엔드 빌드 실패 시
- `npm install` 후 `node_modules/.bin/tsc`, `node_modules/.bin/vite` 존재 확인
- Windows에서 `npm.cmd` 사용 필수 (gradle에서 자동 처리)

### 2. 정적 리소스 경로
- 개발 모드: `frontend/dist` → `backend/src/main/resources/static` (소스 트리)
- 프로덕션 JAR: `BOOT-INF/classes/static/` (JAR 내부)
- Spring Boot는 `classpath:/static/`을 루트로 서빙

### 3. 캐시 문제 해결
```bash
# gradle 캐시 정리
./gradlew clean

# npm 캐시 정리
cd frontend && npm cache clean --force && rm -rf node_modules package-lock.json && npm install
```

---

## 파일 구조 (빌드 후)

```
backend/
├── build/
│   ├── libs/
│   │   └── cloud-infra-admin-0.0.1-SNAPSHOT.jar  ← 실행 가능 JAR
│   └── resources/main/static/                    ← 복사된 프론트엔드 (개발용)
├── src/main/resources/static/                    ← 소스 트리에 복사됨 (gitignore 권장)
│   ├── index.html
│   └── assets/
└── frontend/dist/                                ← npm build 산출물
    ├── index.html
    └── assets/
```

---

## 트러블슈팅

| 문제 | 해결 |
|------|------|
| `tsc` not found / `vite` not found | `npm install --production=false`로 devDependencies 강제 설치 (gradle task에 `--production=false` 추가) |
| `npm install` 해도 devDependencies 안 깔림 | npm 기본값이 `production=true`라서 발생. `package-lock.json` 삭제 후 `npm install --production=false` |
| JAR에 정적 리소스 없음 | `./gradlew clean bootJar`로 전체 리빌드 |
| 포트 충돌 | `application.yml`에서 `server.port` 변경 |
| gradle clean 실패 (JAR 잠금) | 실행 중인 `java -jar` 프로세스 종료 후 재시도 |