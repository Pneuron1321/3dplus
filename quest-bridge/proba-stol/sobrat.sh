#!/bin/sh
# Бридж на столе: сначала тесты компилируются как в CI (только android.jar, без JDK), потом все JVM-тесты.
#   sh sobrat.sh ANDROID_JAR            — проверка android.jar + тесты (итог: сколько прошло)
#   sh sobrat.sh ANDROID_JAR compileKotlin — только сборка кода
# android.jar API 34: curl -fL https://github.com/Sable/android-platforms/raw/master/android-34/android.jar -o android.jar
set -e
D=$(cd "$(dirname "$0")" && pwd); J=${1:?android.jar API 34}; Z=${2:-test}
cd "$D"
if [ "$Z" = test ]; then
  gradle -q compileTestKotlin -PbezJdk -PandroidJar="$J" 2>&1 | grep -E "^e: " | sed 's#file://.*/kotlin/##' | sed 's/^/android.jar: /' || true
fi
gradle -q "$Z" -PandroidJar="$J" 2>&1 | grep -v JAVA_TOOL_OPTIONS | grep -E '^(e|w): |FAILED|Test.*FAILED|expected|at com' | sed 's#file://.*/kotlin/##' | head -40 || true
if [ "$Z" = test ]; then
  cat build/test-results/test/*.xml | grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' |
    awk -F'"' '{t+=$2; f+=$6; e+=$8} END {print "тестов", t, "упало", f, "ошибок", e}'
fi
