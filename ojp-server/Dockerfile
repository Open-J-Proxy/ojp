# Stage 1: Build custom JRE with jlink
# jdeps analyses the fat JAR to compute the exact set of JDK modules required,
# so the module list never drifts out of sync when new dependencies are added.
FROM eclipse-temurin:25-jdk-alpine AS jre-build

COPY target/ojp-server-*-shaded.jar /tmp/ojp-server.jar

RUN MODULES=$(jdeps --ignore-missing-deps --print-module-deps /tmp/ojp-server.jar) && \
    jlink \
        --add-modules ${MODULES} \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-6 \
        --output /custom-jre

# Stage 2: Runtime image
FROM alpine:3.21

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre-build /custom-jre $JAVA_HOME

RUN addgroup -S ojp && adduser -S ojp -G ojp && \
    mkdir -p /opt/ojp && chown ojp:ojp /opt/ojp

WORKDIR /opt/ojp

COPY --chown=ojp:ojp ojp-libs/ /opt/ojp/ojp-libs/
COPY --chown=ojp:ojp target/ojp-server-*-shaded.jar /opt/ojp/ojp-server.jar

USER ojp

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-Dojp.libs.path=/opt/ojp/ojp-libs", \
    "-Duser.timezone=UTC", \
    "-jar", "/opt/ojp/ojp-server.jar"]
