# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- Run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl нужен только для HEALTHCHECK (в jre-образе его нет). --no-install-recommends
# + чистка списков apt держат слой минимальным.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=build /app/target/subscription-service-1.0.0.jar app.jar

USER appuser

EXPOSE 8080

# Контейнер healthy, когда actuator отдаёт UP. start-period щедрый — холодный старт
# Spring Boot + Liquibase-миграции занимают до минуты. docker compose наследует этот
# HEALTHCHECK, поэтому отдельный healthcheck для app в compose не нужен.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
