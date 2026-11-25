#!/bin/sh
# Слова вызова и модель Vosk (2.8.14, 25.11): знает ли модель каждое слово грамматики ушей (assets/triggers.json).
# Слова нет в словаре модели — Vosk молча выкидывает его из грамматики, и фраза («плюс джой») не услышится никогда.
# Запускается из папки quest-bridge после ears/modeli.sh (на GitHub при сборке); пишет в лог и заметкой ::notice.
M=app/src/main/assets/model-ru
# Словарь: words.txt, а у малых моделей (vosk-model-small-ru) его нет — слова зашиты таблицей символов в graph/Gr.fst
# (OpenFst: строка = int32 длина + UTF-8, потом int64 номер). 25.11: первая сборка 2.8.14 показала «нет words.txt».
W=$(find "$M" -name words.txt 2>/dev/null | head -1)
[ -z "$W" ] && W=$(find "$M" -name Gr.fst 2>/dev/null | head -1)
if [ -z "$W" ]; then
  echo "::notice title=Словарь модели::нет ни words.txt, ни Gr.fst — проверить слова вызова нельзя"
  exit 0
fi
python3 - "$W" app/src/main/assets/triggers.json <<'PY'
import json, struct, sys
put = sys.argv[1]
if put.endswith('.txt'):
    slova = {l.split()[0] for l in open(put, encoding='utf-8', errors='replace') if l.strip()}
else:
    data = open(put, 'rb').read()
    class Est:                       # «есть ли слово»: ищем его запись в таблице символов
        def __contains__(self, w):
            b = w.encode('utf-8')
            return struct.pack('<i', len(b)) + b in data
        def __len__(self):
            return 0
    slova = Est()
t = json.load(open(sys.argv[2], encoding='utf-8'))['models']['ru']
nuzhny = set()
for f in list(t['phrases']) + list(t.get('commands', {})) + list(t.get('decoys', [])):
    nuzhny.update(f.split())
net = sorted(w for w in nuzhny if w not in slova)
print('Словарь модели (%s): слов вызова %d; нет в словаре: %s' % (put.rsplit('/', 1)[-1], len(nuzhny), ', '.join(net) or 'все есть'))
print('::notice title=Слово «джой»::%s' % ('есть в словаре модели — «плюс джой» будет слышен' if 'джой' in slova
                                         else 'НЕТ в словаре модели — звать «плюс плюс, джой»'))
PY
