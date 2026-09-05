# Multi-stage: the build tools never reach the running image.
#
# The final image carries a JRE and one application, no Maven, no compiler, no
# source. That is most of the difference between a ~180MB image and a ~700MB one,
# and it removes a compiler from anything an attacker could reach.

# ---------------------------------------------------------------- build stage

FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Dependencies first, on their own layer. The pom changes rarely and the source
# changes constantly, so resolving dependencies before copying src/ means an
# ordinary code change reuses a cached layer instead of re-downloading Spring.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/

# Tests run in CI against a real PostgreSQL through Testcontainers, which needs a
# Docker daemon this build does not have. Skipping them here is deliberate: the
# image build is not the gate, the pipeline is.
#
# extract --layers splits the fat jar into directories that change at different
# rates, so a code change re-transfers the application layer and not the 58MB of
# dependencies behind it. The jar is renamed because its filename carries the
# project version, and an ENTRYPOINT that has to know the version breaks on every
# release.
RUN ./mvnw -B -q clean package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted \
 && mv extracted/application/*.jar extracted/application/app.jar

# -------------------------------------------------------------- runtime stage

FROM eclipse-temurin:21-jre-alpine AS runtime

# Runs as a user with no login and no ownership of the application files, so a
# compromised process cannot rewrite the code it is running.
RUN addgroup -S payments && adduser -S -G payments payments

WORKDIR /app

# Copied in layer order, least to most volatile. Only the last of these changes
# on a normal deploy, so the registry and the host both transfer far less.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER payments

EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM should size its heap from whatever the
# platform actually granted the container, which is not knowable when this image
# is built. Without it a JVM in a 512MB container sizes its heap for the host
# and is killed by the OOM killer under load.
#
# ExitOnOutOfMemoryError because a JVM that has run out of heap cannot be relied
# on to do anything correct afterwards, and a container that dies gets replaced,
# whereas one that limps along keeps failing health checks and serving errors.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
