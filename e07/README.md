# Рабочий порт E07 — штатный SE-вход, 14.09.2026

Это отдельная ветка `e07/native-se` для знакомства с работающим портом. Основная
точка входа для ревью — [RuBridge.java](src/com/stand/bridge/RuBridge.java):
`startSession`, `acceptStockFrame`, `recordSession`, `stopSession`.
Аудиоконтракт объяснён в [E07_AUDIO.md](../E07_AUDIO.md).
Сравнение STT/TTS с фактическим релизом A06 — [AUDIO_COMPARISON.md](AUDIO_COMPARISON.md).

| Участок | Исходники |
|---|---|
| Подключение к OEM, сохранение wake/проверок/exporters | [patch.py](patch.py) |
| Проверка SE-контейнера и OEM-извлечение PCM | [SePcm.java](src/com/stand/bridge/SePcm.java) |
| Конец фразы по PCM | [Utterance.java](src/com/stand/bridge/Utterance.java) |
| GigaAM-v3 CTC и независимый буфер сессии | [GigaAsr.java](src/com/stand/asr/GigaAsr.java) |
| TTS по предложениям, кеш, фокус и полное проигрывание | [TeraTts.java](src/com/stand/tts/TeraTts.java) |
| TeraTTSv2 8-step / ru_f2 | [TeraTTS.java](src/com/stand/tts/tera/TeraTTS.java) |
| Русский маппер / управление секциями багажника | [Ru2Zh.java](src/com/stand/bridge/Ru2Zh.java), [E07Trunk.java](src/com/stand/bridge/E07Trunk.java) |

`src/**/*.java` и `patch.py` скопированы из точного архива установленного APK
`afd7b3a5c8c506ffb76f28e8e51fbb1774aba7ec2c72c7b0829fb3e1a993ca67`.
Хеши — [SOURCE_MANIFEST.json](SOURCE_MANIFEST.json). В публикации изменены только
пути сборщика и тестовых обвязок: внешние зависимости теперь задаются явно.
Рабочая сборка подтверждена на E07 Android 11, SpeechAssistant V17.0126 / code 20260318:
захват 4126 мс, GigaAM 282 мс, полный русский ответ про заряд и запас хода.
Корневые `stand/` и `build.sh` сохраняют A06; для E07 используется только этот каталог.

В исходниках сохранены и неактивные классы архивной сборки: `MicMix` не подключается,
`StandNluReceiver` не зарегистрирован. Они оставлены для точного сопоставления с
проверенным APK, не являются необходимыми этапами штатного входа. Backend A06
в E07 отключён. Телефонные звонки этим портом не исправлены.

## Воспроизведение сборки

Нужны Python 3.11+, JDK 17+ (`JAVA_HOME`; исходная сборка использовала JDK 26),
собственный заводской APK E07 и набор инструментов Android. Заводской SHA-256:
`4919522ba2ca57ea8eab7390dc95db146e125dc903d2a5beb83adb628cf37b57`.
Другую прошивку сборщик намеренно отклоняет: сначала нужно проверить её хуки.

В `E07_TOOLS` должны находиться `android.jar` (API 30), `android-system.jar`
(compile-only system API для AudioPolicy/AudioMix), `d8.jar`, `zipalign`,
`apksigner.jar`, `aosp-platform.x509.pem` и `aosp-platform.pk8.b64`.
Последние два — публичные AOSP platform test keys, соответствующие сертификату
`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.
Набор инструментов и `android-system.jar` нужно предоставить отдельно; скрипт
подготовки ниже загружает только модели и собирает ASR/TTS-зависимости.

```sh
git clone https://github.com/voronoff2803/changan-a06-ru-assistant.git e07/out/upstream-a06
git -C e07/out/upstream-a06 checkout 274e7edd426a547720fb9be1301baa16583e5944
export JAVA_HOME=/path/to/jdk
export E07_TOOLS=/path/to/android-tools
export E07_OEM_CONVERTER=/path/to/jadx-output/sources/com/iflytek/speech/se/PcmConvertor.java
python3 e07/prepare_dependencies.py --stock /path/to/SpeechAssistant.orig.apk
python3 e07/build.py --stock /path/to/SpeechAssistant.orig.apk
```

`E07_OEM_CONVERTER` — исходник, полученный jadx из **своего** штатного APK E07;
он нужен двум проверкам реального OEM-extractor. Проприетарный код не включён.
По умолчанию ожидается `e07/out/oem/sources/com/iflytek/speech/se/PcmConvertor.java`.
`E07_DEPENDENCIES` при необходимости задаёт готовый каталог зависимостей;
иначе используется `e07/out/dependencies`.

Подготовка сверяет все 86 runtime-файлов с [DEPENDENCIES.json](DEPENDENCIES.json):
GigaAM из pinned sherpa-экспорта, согласованный TeraTTSv2 8-step из pinned HF revision,
Java/JNI и остальные Tera-файлы из исходной ревизии A06, ORT из собственного APK E07.
Десктопный ORT используется только для предварительного синтеза фиксированных реплик.

Сборка проверяет хуки обратным разбором DEX, исключает compile-only stubs из APK,
сохраняет штатный манифест/versionCode, проверяет подпись и ZIP/DEX. Результат:
`e07/out/build/SpeechAssistant-E07-RU.apk` и `verification.json`. Сборщик не устанавливает APK.
Новая упаковка не обязана совпасть по SHA с архивным APK: runtime-исходники сверяются
отдельно, а provenance/пути/ZIP и кеш могут отличаться.

## Проверки

```sh
sh e07/tests/run_regression.sh
sh e07/tests/run_runtime_tests.sh
python3 e07/tests/run_capture_tests.py
python3 e07/tests/run_se_pcm_tests.py
python3 e07/tests/run_se_probe_tests.py
python3 e07/tests/run_micmix_tests.py
python3 e07/tests/test_prepare_dependencies.py
python3 e07/tests/test_smali_build.py /path/to/SpeechAssistant.orig.apk
```

Проверки capture/extractor требуют `E07_OEM_CONVERTER`, runtime требует
`E07_TOOLS/android.jar`; остальным моделям эти проверки не обращаются.
Для фоновой проверки на GitHub доступны тесты, которым не нужен штатный APK.

Публикуемый вариант сборщика проверен полной сборкой: `classes7.dex` побайтно
совпал с установленным архивом; `classes5.dex` отличается бинарной упаковкой,
но его полный обратный разбор (8052 класса) побайтно совпал. Результаты —
[measurements/source-build.json](measurements/source-build.json).

APK, веса, инструменты, закрытые данные приложений и полные автомобильные журналы
в эту ветку не включены. Лицензия модификации — [PolyForm Noncommercial](LICENSE),
автор исходного A06-модуля — Tecrow. Notices зависимостей — [licenses/](licenses/).
У весов TeraTTSv2 в зафиксированной model card отдельная лицензия не указана;
лицензия MIT на код не объявляется лицензией весов.
