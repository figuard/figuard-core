# Build stage — no local Java or Maven required
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies separately from source — only re-downloads when pom.xml changes
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Build the application
COPY src ./src
RUN ./mvnw package -DskipTests -q && cp target/core-*.jar target/app.jar

# Runtime stage — minimal JRE image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && mkdir -p /data
EXPOSE 8080
# Entrypoint generates+persists a unique pepper and webhook key on first boot
# unless they are explicitly provided. Persisted to /data (mount a volume there).
ENTRYPOINT ["/app/docker-entrypoint.sh"]
