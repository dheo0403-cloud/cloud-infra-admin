# ==========================================
# JAR 실행용 런타임 이미지 (GCP gRPC Netty glibc 호환)
# ==========================================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 한국 타임존 설정
ENV TZ=Asia/Seoul
RUN apt-get update && apt-get install -y --no-install-recommends tzdata \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

# 보안용 일반 사용자 생성
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# 같은 폴더에 있는 JAR 파일을 app.jar로 복사
COPY *.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
