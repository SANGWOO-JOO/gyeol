# api · worker · external-fetcher 공통 이미지. jar는 밖에서 ./gradlew build로 만든다 (scripts/k3d-up.sh)
#   docker build -f deploy/docker/backend.Dockerfile --build-arg MODULE=api -t gyeol/api:dev .
FROM eclipse-temurin:21-jre-alpine
ARG MODULE
RUN addgroup -S -g 1000 app && adduser -S -u 1000 -G app app
WORKDIR /app
COPY backend/${MODULE}/build/libs/${MODULE}-0.1.0.jar app.jar
USER 1000:1000
# 컨테이너 메모리 제한에 맞춰 힙을 잡는다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
