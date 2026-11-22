# One image for the whole application.
# Build: docker build -t lms .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# The pom alone first, so dependency download is cached while only sources change.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Running as root in a container buys nothing and costs a lot if the process is ever compromised.
RUN addgroup -S lms && adduser -S lms -G lms
COPY --from=build /build/target/*.jar app.jar
USER lms

EXPOSE 8080
# MaxRAMPercentage, not -Xmx: the JVM then respects whatever limit the container is given.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
