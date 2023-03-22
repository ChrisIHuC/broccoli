FROM maven:3.8.5 as builder

WORKDIR /build/
COPY ./src /build/src
COPY ./pom.xml /build/
RUN mvn --no-transfer-progress --batch-mode --update-snapshots --also-make package

FROM openjdk:21-ea-13-slim-bullseye

WORKDIR /apps/broccoli
COPY --from=builder /build/target/broccoli-*.jar /apps/broccoli/broccoli.jar
COPY ./k8s/broccoli-server/entrypoint.sh entrypoint.sh
COPY ./config.yml config.yml
EXPOSE 8080
CMD ["./entrypoint.sh"]
