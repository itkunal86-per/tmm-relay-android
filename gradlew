#!/usr/bin/env sh

APP_HOME=$(cd "$(dirname "$0")" && pwd)
DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVA_CMD=${JAVA_HOME:+"$JAVA_HOME/bin/java"}
if [ -z "$JAVA_CMD" ]; then JAVA_CMD=java; fi
exec "$JAVA_CMD" ${DEFAULT_JVM_OPTS[@]} -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
