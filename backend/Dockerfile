FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system festa \
    && useradd --system --gid festa --home-dir /app --shell /usr/sbin/nologin festa

COPY target/SSAFESTA-0.0.1-SNAPSHOT.jar app.jar

USER festa

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
