# ==============================================================================
# [파일 역할]
# Render 클라우드 배포를 위한 멀티스테이지(Multi-Stage) Dockerfile
#
# [담당 기능]
# 1. Stage 1 (Builder): Eclipse Temurin JDK 17 환경에서 Gradle Wrapper를 실행하여
#    `frontend/` 정적 리소스와 `backend/` Spring Boot 코드를 단일 실행형 JAR
#    (`yonsei119-backend.jar`)로 패키징합니다.
# 2. Stage 2 (Runtime): 경량 Eclipse Temurin JRE 17 이미지를 사용하여 컨테이너 크기를 줄이고,
#    Render가 동적으로 주입하는 `$PORT` 환경변수에 맞춰 서버를 구동합니다.
#
# [수정 시 영향 및 예시]
# - Render 유료 플랜에서 JVM 힙 메모리 비율을 75%에서 80%로 늘리고 싶다면:
#   변경 전: ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
#   변경 후: ENV JAVA_OPTS="-XX:MaxRAMPercentage=80.0 -Dfile.encoding=UTF-8"
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Gradle Build (Frontend + Backend 통합 패키징)
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /workspace

# Gradle Wrapper 및 프로젝트 설정 파일 먼저 복사 (Docker 레이어 캐시 활용)
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
COPY backend/build.gradle ./backend/build.gradle

# 실행 권한 부여 및 의존성 레이어 사전 다운로드 (소스코드만 수정된 경우 재다운로드 생략)
RUN chmod +x ./gradlew && ./gradlew :backend:dependencies --no-daemon

# 프론트엔드 및 백엔드 전체 소스 복사
COPY frontend ./frontend
COPY backend/src ./backend/src

# copyFrontendToStatic 태스크가 자동 실행되어 frontend/ 파일들이 JAR 내부 static/에 포함됩니다.
RUN ./gradlew :backend:bootJar --no-daemon -x test

# ------------------------------------------------------------------------------
# Stage 2: Production Runtime (Render Web Service 호환)
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

WORKDIR /app

# 빌드 스테이지에서 생성된 단일 실행형 JAR 복사
COPY --from=builder /workspace/backend/build/libs/yonsei119-backend.jar /app/app.jar

# Render 기본 포트 및 컨테이너 메모리 보호용 JVM 옵션
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

EXPOSE 8080

# Render는 실행 시 $PORT 환경변수를 주입하므로 이를 우선 바인딩하여 실행합니다.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
