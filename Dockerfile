# ── Stage 1: Build ──
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# ── Stage 2: Runtime ──
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S mailer && adduser -S mailer -G mailer

# Copy Google OAuth credentials (as root before switching user)
COPY google-oauth-credentials.json /etc/mailer/google-oauth-credentials.json
RUN chmod 600 /etc/mailer/google-oauth-credentials.json

USER mailer

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 9199

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-XX:+UseZGC", \
    "-Xmx512m", \
    "-Dserver.port=9199", \
    "-jar", "app.jar"]
