#============================================================================
#CloseAuth Backend - Multi-stage Docker Build
#OAuth2 Authorization Server (Spring Boot 3.5/ Java 21)
#============================================================================

ARG DOCKER_REGISTRY=docker.io
FROM eclipse-temurin:21-jdk-alpine AS jre-build

ARG JAR_FILE=target/*.jar
ARG ENTRYPOINT_FILE=entrypoint.sh


# Add entry point
COPY ${ENTRYPOINT_FILE} /entrypoint.sh
# Add application jar
COPY ${JAR_FILE} /app.jar

# Make entrypoint executable and extract spring boot layers
RUN chmod +x /entrypoint.sh && \
    java -Djarmode=tools -jar /app.jar extract --layers --launcher

# Generate required modules and build a custom JRE
RUN jdeps --recursive \
          --print-module-deps \
          --ignore-missing-deps \
          --multi-release 21 \
          --class-path "app/dependencies/BOOT-INF/lib/*:app/snapshot-dependencies/BOOT-INF/lib/*" \
          --module-path "app/dependencies/BOOT-INF/lib/*:app/snapshot-dependencies/BOOT-INF/lib/*" \
            app/application/ \
            > modules.txt && \
    jlink --add-modules $(cat modules.txt) \
          --output jre \
          --strip-debug \
          --compress=zip-9 \
          --no-header-files \
          --no-man-pages

# Stage 2: Production runtime
FROM ${DOCKER_REGISTRY}/alpine:latest

# App user configuration
ARG APP_USER=closeauth
ARG APP_DIR=/home/$APP_USER
ARG UID=1000
ARG GID=1000

ENV JAVA_HOME=/opt/java
ENV PATH="$PATH:$JAVA_HOME/bin"

# Copy custom JRE from build stage
COPY --from=jre-build /jre $JAVA_HOME

# Create system group and user
RUN addgroup -g $GID $APP_USER && \
    adduser --uid $UID --disabled-password --no-create-home --shell /usr/sbin/nologin \
            $APP_USER --ingroup $APP_USER && \
    mkdir -p $APP_DIR && \
    chown -R $APP_USER:$APP_USER $APP_DIR

WORKDIR $APP_DIR

# Copy extracted Spring boot layers
COPY --from=jre-build /app/dependencies/ ./
COPY --from=jre-build /app/snapshot-dependencies/ ./
COPY --from=jre-build /app/spring-boot-loader/ ./
COPY --from=jre-build /app/application/ ./
COPY --from=jre-build /entrypoint.sh ./entrypoint.sh

#Create logs directory
RUN mkdir -p logs && chown $APP_USER:$APP_USER logs

USER $APP_USER

EXPOSE 9000

ENTRYPOINT ["./entrypoint.sh"]
