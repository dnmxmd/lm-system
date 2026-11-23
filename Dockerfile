# One image for the whole application.
# Build: docker build -t lms .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY src src

# A cache mount instead of a separate dependency:go-offline layer. That step resolved the whole
# tree a second time and was slow and incomplete; this keeps ~/.m2 between builds, so only
# genuinely new dependencies are fetched.
# -DskipTests skips running the tests but still compiles them, which is deliberate: it catches a
# test that no longer matches the code it tests.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

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
