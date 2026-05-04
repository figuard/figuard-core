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
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
