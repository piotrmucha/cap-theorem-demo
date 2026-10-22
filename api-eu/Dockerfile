# See api-us/Dockerfile for why we install a plain Gradle
# distribution here instead of shipping a wrapper jar.
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
