ARG DOCKER_HUB_PREFIX=
FROM ${DOCKER_HUB_PREFIX}gradle:9.5.1-jdk21 AS build

WORKDIR /home/gradle/project
COPY --chown=gradle:gradle build.gradle.kts settings.gradle.kts gradle.properties ./
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle src ./src
RUN gradle buildFatJar --no-daemon

FROM ${DOCKER_HUB_PREFIX}eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        libpulse0 \
        libudev1 \
        libx11-6 \
        libxfixes3 \
        libxcomposite1 \
        libxrandr2 \
        libxdamage1 \
        libxtst6 \
        libxss1 \
        libasound2 \
        libgbm1 \
        libpango-1.0-0 \
        libcairo2 \
        libatk1.0-0 \
        libatk-bridge2.0-0 \
        libxkbcommon0 \
        libdrm2 \
        libnss3 \
        libdbus-1-3 \
        libexpat1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system kodee \
    && useradd --system --gid kodee --home-dir /app --shell /usr/sbin/nologin kodee
WORKDIR /app
COPY --from=build --chown=kodee:kodee /home/gradle/project/build/libs/*-all.jar /app/server.jar
COPY --chown=kodee:kodee docker/application.yaml /app/application.yaml

USER kodee
EXPOSE 8011
ENTRYPOINT ["java", "-jar", "/app/server.jar", "-config=/app/application.yaml"]
