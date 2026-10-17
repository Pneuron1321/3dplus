#!/bin/sh
# Макет кадра эфира 1080p настоящим кодом плашек (Nalozhenie, Raskladka, Okno, Titry, Komnata):
#   sh zapusk.sh ANDROID_JAR ПАПКА → ПАПКА/gosti-maket.png, gosti-maket-3.png, gosti-maket-vnizu.png
# Шрифт — Roboto (как у Android) из npm roboto-fontface, WOFF → TTF своим woff2ttf.py. Нужны gradle и JDK 21.
set -e
D=$(cd "$(dirname "$0")" && pwd)
J=${1:?android.jar API 34}; O=${2:-$D/maket-out}
mkdir -p "$O/shrift"
if [ ! -f "$O/shrift/Roboto-Bold.ttf" ]; then
  T=$(mktemp -d)
  U=$(curl -sS https://registry.npmjs.org/roboto-fontface/latest | python3 -c "import json,sys;print(json.load(sys.stdin)['dist']['tarball'])")
  curl -sS -o "$T/r.tgz" "$U"
  tar -xzf "$T/r.tgz" -C "$T" package/fonts/roboto/Roboto-Bold.woff package/fonts/roboto/Roboto-Regular.woff
  for f in Bold Regular; do python3 -I "$D/woff2ttf.py" "$T/package/fonts/roboto/Roboto-$f.woff" "$O/shrift/Roboto-$f.ttf"; done
  rm -rf "$T"
fi
cd "$D" && gradle -q maket -PandroidJar="$J" -Pshrift="$O/shrift" -PmaketArgs="$O"
