#!/bin/sh

# Ensure the logs directory exists (bind mounts may create it as root)

mkdir -p logs 2>/dev/null || true

if ! echo "$JAVA_OPTS" | grep -q -- '-Dspring.profiles.active='; then
  if [ -n "$SPRING_PROFILE" ]; then
    echo "Setting application profile as [$SPRING_PROFILE]"
    JAVA_OPTS="${JAVA_OPTS:+$JAVA_OPTS}-Dspring.profiles.active=$SPRING_PROFILE"
  else
    echo "Setting application profile as [docker]"
    JAVA_OPTS="${JAVA_OPTS:+$JAVA_OPTS}-Dspring.profiles.active=docker"
  fi
fi

if ! echo "$JAVA_OPTS" | grep -q -- '-Dlog.level='; then
  if [ -n "$LOG_LEVEL" ]; then
    echo "Setting application logging level as [$LOG_LEVEL]"
    JAVA_OPTS="${JAVA_OPTS:+$JAVA_OPTS}-Dlog.level=$LOG_LEVEL"
  fi
fi

exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher
