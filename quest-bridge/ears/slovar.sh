#!/bin/sh
# Слова вызова и модель Vosk (2.8.14, 25.11): знает ли модель каждое слово грамматики ушей (assets/triggers.json).
# Слова нет в словаре модели — Vosk молча выкидывает его из грамматики, и фраза («плюс джой») не услышится никогда.
# Запускается из папки quest-bridge после ears/modeli.sh (на GitHub при сборке); пишет в лог и заметкой ::notice.
M=app/src/main/assets/model-ru
W=$(find "$M" -name words.txt 2>/dev/null | head -1)
if [ -z "$W" ]; then
  echo "::notice title=Словарь модели::в модели нет words.txt — проверить слова вызова нельзя"
  exit 0
fi
python3 - "$W" app/src/main/assets/triggers.json <<'PY'
import json, sys
slova = {l.split()[0] for l in open(sys.argv[1], encoding='utf-8', errors='replace') if l.strip()}
t = json.load(open(sys.argv[2], encoding='utf-8'))['models']['ru']
nuzhny = set()
for f in list(t['phrases']) + list(t.get('commands', {})) + list(t.get('decoys', [])):
    nuzhny.update(f.split())
net = sorted(w for w in nuzhny if w not in slova)
print('Словарь модели: %d слов; слов вызова %d; нет в словаре: %s' % (len(slova), len(nuzhny), ', '.join(net) or 'все есть'))
print('::notice title=Слово «джой»::%s' % ('есть в словаре модели — «плюс джой» будет слышен' if 'джой' in slova
                                         else 'НЕТ в словаре модели — звать «плюс плюс, джой»'))
PY
