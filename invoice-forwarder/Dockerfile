# ── Stage 1: Build ──
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ── Stage 2: Runtime ──
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S forwarder && adduser -S forwarder -G forwarder \
    && mkdir -p /var/lib/invoicedirect/inbox /var/lib/invoicedirect/inbox/processed \
                /var/lib/invoicedirect/inbox/failed \
    && chown -R forwarder:forwarder /var/lib/invoicedirect
USER forwarder

COPY --from=builder /build/target/invoice-forwarder-*.jar app.jar

EXPOSE 9099

ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
