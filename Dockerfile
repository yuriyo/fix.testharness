# syntax=docker/dockerfile:1
# FIX Test Harness
# Stage 1: build fat-jar with Maven (no local JDK/Maven required)
# Stage 2: minimal JRE runtime image

FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Download dependencies first (layer cache)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build
COPY src ./src
RUN mvn package -DskipTests -q


FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /build/target/fix-testharness-*.jar app.jar

# Bake in default config and scenarios; can be overridden at runtime via bind mounts
COPY config    ./config
COPY scenarios ./scenarios

# store/ and logs/ are written at runtime — mount from host for persistence
RUN mkdir -p store logs

ENTRYPOINT ["java", "-jar", "app.jar"]
# Default: print usage (no args → app will show help or fail fast)
