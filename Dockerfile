FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/core-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
