#!/bin/sh
# Модель Vosk для ушей (русская; английскую убрали в 2.2.2 — память) → app/src/main/assets/model-<язык> (+ файл uuid для распаковки в шлеме).
# Запускается на GitHub из папки quest-bridge; в git модели не храним.
set -e
get() {   # язык имя-архива
  M=app/src/main/assets/model-$1
  [ -f "$M/uuid" ] && [ "$(cat "$M/uuid")" = "$2" ] && { echo "$2 — уже есть"; return; }
  curl -sSfL --retry 3 -o "/tmp/$2.zip" "https://alphacephei.com/vosk/models/$2.zip"
  rm -rf "/tmp/$2" "$M"
  unzip -q "/tmp/$2.zip" -d /tmp
  mkdir -p app/src/main/assets
  mv "/tmp/$2" "$M"
  echo "$2" > "$M/uuid"
  echo "$2 — скачана: $(du -sh "$M" | cut -f1)"
}
get ru vosk-model-small-ru-0.22
