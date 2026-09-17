#!/bin/sh
#
# Gradle start up script for UN*X
#
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)
GRADLE_USER_HOME="${GRADLE_USER_HOME:-"$HOME/.gradle"}"
GRADLE_HOME="$GRADLE_USER_HOME/wrapper/dists/gradle-8.6-bin/*/gradle-8.6"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Fall back to system java
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
