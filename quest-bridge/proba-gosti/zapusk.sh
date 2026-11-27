#!/bin/sh
# Проба гостей на столе: стенд (Kotlin, JVM) + Chromium (Playwright). Нужны: gradle-проект сборки на столе
# (H — с android.jar для компиляции и concentus), node с playwright-core, Chromium, ffmpeg.
#   sh zapusk.sh H ПАПКА_ОТЧЁТА   (H — например quest-bridge/proba-stol; тогда ANDROID_JAR=путь к android.jar)
set -e
H=$1; OUT=$2; PORT=${PORT:-18771}
R=$(cd "$(dirname "$0")/.." && pwd)
PW=${PW:-/home/user/3dplus/node_modules/playwright-core/index.mjs}
CHROME=${CHROME:-/opt/pw-browsers/chromium-1194/chrome-linux/chrome}
rm -rf "$OUT"; mkdir -p "$OUT"
ffmpeg -loglevel error -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=60" -ac 1 "$OUT/ton440.wav"
cd "$H"
gradle -q --offline stol ${ANDROID_JAR:+-PandroidJar=$ANDROID_JAR} -PstolArgs="$PORT $R/app/src/main/assets/gosti $OUT 90" > "$OUT/stend.log" 2>&1 &
STEND=$!
node "$R/proba-gosti/proba.mjs" "$PORT" "$OUT" "$PW" "$CHROME" "$OUT/ton440.wav" || true
wait $STEND || true
cat "$OUT/otchet.json"; echo
for f in "$OUT"/gost*.ivf; do [ -f "$f" ] && ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=width,height,nb_read_frames -of csv=p=0 "$f" | sed "s#^#$(basename $f): #"; done
for f in "$OUT"/gost*.wav; do [ -f "$f" ] && python3 - "$f" <<'PY'
import sys, wave, numpy as np
w = wave.open(sys.argv[1]); a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float)
if len(a) < 48000: print(sys.argv[1].split('/')[-1], 'коротко', len(a)); sys.exit()
a = a[24000:]; s = np.abs(np.fft.rfft(a * np.hanning(len(a)))); f = np.fft.rfftfreq(len(a), 1/48000)
print(sys.argv[1].split('/')[-1], 'секунд %.1f' % (len(a)/48000), 'частота %.0f Гц' % f[np.argmax(s)], 'уровень %.0f' % np.sqrt((a**2).mean()))
PY
done
