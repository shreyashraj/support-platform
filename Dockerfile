FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml openapi.yaml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home app
WORKDIR /app
COPY --from=build /app/target/support-platform-0.1.0.jar app.jar
RUN mkdir -p /app/data && chown app /app/data
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
