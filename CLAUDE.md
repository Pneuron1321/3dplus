# Правила работы с Александром (долгосрочно)

- **Сборку приложения не запускать без «одобряю» Александра.** Сборка APK запускается сама, когда код
  quest-bridge попадает в открытый 3dplus (зеркало, prilozhenie.yml). Поэтому зеркало quest-bridge в 3dplus
  и любой другой запуск сборки — только после его явного «одобряю» (или «начинай сборку»). До этого — правки,
  стенд proba-maket, тесты proba-stol и коммит в закрытый quest-claude, без зеркала.
- Финальный результат (модель, оформление) — тоже только после «одобряю»; пробы — под флагами.
- Говорить просто, кратко, по-русски, на «ты»; шаги — нумерованным списком.

# Состояние работы (29.10, для продолжения после сжатия контекста)

- **Приложение (мост 2):** стоит 2.7.8 (собрана 29.10). **2.7.9 готова в закрытом quest-claude, НЕ собрана — ждёт «одобряю»**
  (зеркало quest-bridge + shlem2 в 3dplus = сборка). В 2.7.9: цвета рамок (модератор #FF4520 мандариново-грейпфрутовый, свечение
  ×1,25; гостям случайный из 8: фиолет, тёмный ярко-зелёный, жёлтый, рубиновый, белый, шоколадный, песочный, мятный — Komnata.RAMKI/
  SLUCHAYNYE); звук фоловера assets/zvuki/folover.mp3 (из ролика Александра) в Opoveshchenie, «спасибо» после звука (donaty ZVUK_SEK);
  приветствие зрителей на чистом английском, ники словами (zriteli vslukh); запреты соведущей в коде (_otkaz_pravil); полные
  Транзитор и Фарватер (shlem/deep-transitor.md, farvater.md → obnovi кладёт в /root/.vedushchiy/; Александр разрешил публично);
  козырь «Отмычка» ⚿ (shlem/skills/otmychka, ставится вручную в /root/.claude/skills/); клип шлема: режимы `efir klip
  efir|otdelno|vykl` (/klip/upr), по умолчанию efir — клип шлема = байты эфира (с окном камеры, ноль нагрузки; Александр 29.10:
  «можно и с окном камеры»); otdelno — KlipShlem (своё 1080p без окна, фоновый, 5 к/с при тесноте).
  Клипы камеры и шлема — на одно время (KlipSinhron: эфиру ключевой вслед за камерой, общие часы в KlipZapis, имена с одним
  временем). Прозрачность плиток гостей: `gosti prozrachnost [ИМЯ] N`. Отчёт о падениях за 28.10 не ждём (Александр: «скорее
  всего нагрузка VRChat»).
- **Следующее после «одобряю»:** зеркало (сперва `python3 shlem/sobrat-sumy /home/user/3dplus` и слить с прежним obnovi-sumy через
  sort -u), версия уже 2.7.9/279; строки установки: APK, obnovi из shlem2 по sha, **`! obnovi pravila` (Александр сам)**, навык
  otmychka: `! mkdir -p /root/.claude/skills/otmychka/references && curl … SKILL.md и references/otmychka.md`, `kill $(cat /tmp/golos.pid)`.
- **Возможно позже:** «Заплатка» (docs/OTMYCHKA-KLIP.md) — клип шлема без окна камеры почти без нагрузки (запись только
  области под окном + сборка после эфира) — только если Александр снова захочет без окна.
- **2.7.8 (собрана):** «клип клип клип» (уши моста, KlipMp4 до 185 с, KlipZapis 200 с, /klip), плашка NEW FOLLOWER/SUB/RAID
  (Opoveshchenie, слой "al", /opoveshchenie, фоловеры StreamElements в donaty), gosti razmer/cvet, полный экран у гостя,
  голос Плюса мужской (Эндрю; golos pol m|zh|moy; свой — ElevenLabs: kluch eleven, golos moy ID), переводы в роде голоса.
- **Ждём от Александра:** отзыв о 3D-пробе telo=setka.
- **Стенды:** тесты `sh quest-bridge/proba-stol/sobrat.sh ANDROID_JAR` (283), макет `proba-maket/zapusk.sh`, гости в браузере
  `PW=/opt/node-tools/node_modules/playwright-core/index.mjs CHROME=/opt/pw-browsers/chromium-1243/chrome-linux64/chrome
  ANDROID_JAR=… sh proba-gosti/zapusk.sh proba-stol OUT`. android.jar: scratchpad aplat/android-34 (качать с
  raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar). Контейнер может сброситься — коммитить сразу.
- Секреты не читать и не печатать; модели в коммитах не называть; PR не делать.
