# Runtime-only image — jar is built on the host (mvn package -DskipTests)
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S mailer && adduser -S mailer -G mailer

# Google OAuth2 credentials are NOT baked into the image — a secret in an image
# layer leaks to anyone who can pull it. They are only read when
# massmailer.gmail-oauth2.enabled=true (hardcoded false today). To enable, mount
# the file at runtime and point GOOGLE_OAUTH2_CREDENTIALS_PATH at it:
#   -v ./google-oauth-credentials.json:/etc/mailer/google-oauth-credentials.json:ro

USER mailer

COPY target/mass-mailer-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 9199

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-XX:+UseZGC", \
    "-Xmx512m", \
    "-Dserver.port=9199", \
    "-jar", "app.jar"]
