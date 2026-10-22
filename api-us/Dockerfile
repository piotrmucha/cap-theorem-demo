# Multi-stage build. We don't ship a Gradle wrapper jar (it's a binary and
# there was no local Gradle install to generate one from) -- instead we
# install a plain Gradle distribution straight into the build stage, which
# is exactly what the wrapper itself would do at build time anyway.
FROM eclipse-temurin:25-jdk AS build

ARG GRADLE_VERSION=9.1.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/gradle-${GRADLE_VERSION}/bin:${PATH}"

WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle build --no-daemon -x test

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
