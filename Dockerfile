# Runtime-only image — jar is built on the host (mvn package -DskipTests)
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S mailer && adduser -S mailer -G mailer

COPY google-oauth-credentials.json /etc/mailer/google-oauth-credentials.json
RUN chmod 600 /etc/mailer/google-oauth-credentials.json

USER mailer

COPY target/mass-mailer-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 9199

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-XX:+UseZGC", \
    "-Xmx512m", \
    "-Dserver.port=9199", \
    "-jar", "app.jar"]
