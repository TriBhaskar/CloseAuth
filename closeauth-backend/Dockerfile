#============================================================================
#CloseAuth Backend - Multi-stage Docker Build
#OAuth2 Authorization Server (Spring Boot 3.5/ Java 21)
#============================================================================

FROM eclipse-temurin:21-jdk-alpine AS build

ARG JAR_FILE=target/*.jar
ARG ENTRYPOINT_FILE=entrypoint.sh

# Add entrypoint and application jar
COPY ${ENTRYPOINT_FILE} /entrypoint.sh
COPY ${JAR_FILE} /app.jar

# Make entrypoint executable and extract Spring Boot layers
RUN chmod +x /entrypoint.sh && \
    java -Djarmode=tools -jar /app.jar extract --layers --launcher

# Stage 2: Production runtime - full JRE, no jlink
FROM eclipse-temurin:21-jre-alpine

# App user configuration
ARG APP_USER=closeauth
ARG APP_DIR=/home/$APP_USER
ARG UID=1000
ARG GID=1000

# Create system group and user
RUN addgroup -g $GID $APP_USER && \
    adduser --uid $UID --disabled-password --no-create-home --shell /usr/sbin/nologin \
            $APP_USER --ingroup $APP_USER && \
    mkdir -p $APP_DIR && \
    chown -R $APP_USER:$APP_USER $APP_DIR

WORKDIR $APP_DIR

# Copy extracted Spring Boot layers (ordered by change frequency for better layer caching)
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/application/ ./
COPY --from=build /entrypoint.sh ./entrypoint.sh

# Create logs directory
RUN mkdir -p logs && chown $APP_USER:$APP_USER logs

USER $APP_USER

EXPOSE 9000

ENTRYPOINT ["./entrypoint.sh"]