"""Слух (Whisper, shlem/sluh) — живыми голосами до шлема.

edge-tts говорит фразы ru/uk/en → WAV 16 кГц → тот же скрипт shlem/sluh, что в
шлеме (модель small, int8). Ждём в ответе ключевые слова. Запуск на GitHub из
папки quest-bridge: python ears/sluh_proverka.py
"""
import asyncio
import json
import subprocess
import sys
import wave

import edge_tts

CASES = [('ru-RU-SvetlanaNeural', 'посмотри, что написано на экране', ['экран']),
         ('ru-RU-DmitryNeural', 'сколько патронов у меня осталось', ['патрон']),
         ('ru-RU-SvetlanaNeural', 'открой карту и скажи, куда идти дальше', ['карт']),
         ('uk-UA-OstapNeural', 'подивись, що написано на екрані', ['екран', 'экран']),
         ('en-US-GuyNeural', 'what is written on the screen', ['screen']),
         ('en-US-JennyNeural', 'how many enemies are left', ['enem'])]


async def speak(voice, text, path):
    for attempt in range(6):
        try:
            mp3 = b''.join([c['data'] async for c in edge_tts.Communicate(text, voice).stream() if c['type'] == 'audio'])
            if mp3:
                break
        except Exception:
            pass
        await asyncio.sleep(3 * (attempt + 1))
    else:
        return False
    pcm = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', 'pipe:0', '-ar', '16000', '-ac', '1',
                          '-f', 's16le', 'pipe:1'], input=mp3, capture_output=True).stdout
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm)
    return True


async def main():
    files = []
    for i, (voice, text, keys) in enumerate(CASES):
        path = '/tmp/sluh%d.wav' % i
        files.append(path if await speak(voice, text, path) else None)
    p = subprocess.Popen([sys.executable, '../shlem/sluh'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    print('модель:', p.stdout.readline().strip(), flush=True)
    bad = 0
    for (voice, text, keys), path in zip(CASES, files):
        if path is None:
            print('НЕТ ЗВУКА', voice, text)
            continue
        p.stdin.write(path + '\n'); p.stdin.flush()
        r = json.loads(p.stdout.readline())
        ok = any(k in r.get('text', '').lower() for k in keys)
        bad += not ok
        print('%s %-22s %-40s → %s «%s»%s' % ('OK ' if ok else 'ПЛОХО', voice, text, r.get('lang'), r.get('text'),
              ('  ОШИБКА: ' + r['error']) if r.get('error') else ''), flush=True)
    p.stdin.close()
    print('\nИтог: ошибок %d из %d' % (bad, len(CASES)))
    sys.exit(1 if bad else 0)


asyncio.run(main())
